package com.moneylens.service;

import com.moneylens.entity.Transaction;
import com.moneylens.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DeduplicationService
 *
 * Handles two responsibilities:
 *
 *   1. Hash computation — produces a deterministic SHA-256 hash for any
 *      transaction so that the same real-world transaction appearing in
 *      two overlapping PDFs produces the same hash.
 *
 *   2. Batch duplicate detection — given a list of incoming transactions,
 *      returns only those that are genuinely new for this user (i.e. not
 *      already present in ANY of their statements).
 *
 * ── Hash input format ────────────────────────────────────────────────────────
 *
 *   date|amount|normalizedDescription|type
 *
 *   Where:
 *     date        → ISO format (2026-01-15)
 *     amount      → plain decimal, no trailing zeros (500.00 → "500.00")
 *     description → lowercased, whitespace collapsed, trimmed
 *     type        → "DEBIT" or "CREDIT"
 *
 *   Example:
 *     "2026-01-15|500.00|upi-zomato-payzomato|DEBIT"
 *     → SHA-256 → "3a7f9b2c..."
 *
 * ── Why not use transaction ID from the bank? ────────────────────────────────
 *
 *   Bank-issued reference numbers are not consistently present in PDF
 *   statements. Some banks include them; many don't. The hash approach
 *   works universally without relying on bank-specific formatting.
 *
 * ── False positive rate ──────────────────────────────────────────────────────
 *
 *   Two DIFFERENT transactions produce the same hash only if they share
 *   the same date, amount, description, and type. In practice this is
 *   rare but possible (e.g. two ₹500 Zomato orders on the same day).
 *   This is an acceptable trade-off — the user loses one duplicate-looking
 *   row rather than seeing the same transaction twice.
 */
@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);

    private final TransactionRepository transactionRepository;

    public DeduplicationService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes and sets the dedupHash on the given transaction.
     * Call this BEFORE persisting any transaction.
     *
     * Idempotent — safe to call multiple times on the same object.
     */
    public void stamp(Transaction transaction) {
        String hash = computeHash(
                transaction.getDate(),
                transaction.getAmount(),
                transaction.getDescription(),
                transaction.getType()
        );
        transaction.setDedupHash(hash);
    }

    /**
     * Stamps a list of transactions in place.
     * Does NOT filter — call filterNew() separately if needed.
     */
    public void stampAll(List<Transaction> transactions) {
        transactions.forEach(this::stamp);
    }

    /**
     * Given a list of incoming transactions (already stamped with hashes)
     * and the owning user's ID, returns only those transactions that do
     * NOT already exist in the canonical timeline for that user.
     *
     * Uses a single batch query instead of N individual lookups.
     *
     * @param userId       the user whose timeline we check against
     * @param incoming     transactions already stamped with dedupHash
     * @return             subset of incoming that are genuinely new
     */
    public List<Transaction> filterNew(UUID userId, List<Transaction> incoming) {
        if (incoming.isEmpty()) return List.of();

        // Collect all hashes from incoming batch
        List<String> incomingHashes = incoming.stream()
                .map(Transaction::getDedupHash)
                .collect(Collectors.toList());

        // Single round-trip: ask DB which hashes already exist for this user
        Set<String> existingHashes = Set.copyOf(
                transactionRepository.findExistingDedupHashes(userId, incomingHashes)
        );

        List<Transaction> newTransactions = incoming.stream()
                .filter(t -> !existingHashes.contains(t.getDedupHash()))
                .collect(Collectors.toList());

        int skipped = incoming.size() - newTransactions.size();
        if (skipped > 0) {
            log.info("Dedup: skipping {} duplicate transactions for user {} ({} new out of {})",
                    skipped, userId, newTransactions.size(), incoming.size());
        }

        return newTransactions;
    }

    /**
     * Stamps all transactions in the list, then filters to only new ones.
     * Convenience method combining stampAll() + filterNew().
     */
    public List<Transaction> stampAndFilter(UUID userId, List<Transaction> incoming) {
        stampAll(incoming);
        return filterNew(userId, incoming);
    }

    /**
     * Computes the dedup hash for a transaction without modifying it.
     * Useful for checking existence before building the entity.
     */
    public String computeHash(LocalDate date, BigDecimal amount,
                              String description, Transaction.Type type) {
        String normalizedDesc = normalizeDescription(description);
        String plainAmount    = amount != null
                ? amount.stripTrailingZeros().toPlainString()
                : "0";

        String raw = date + "|" + plainAmount + "|" + normalizedDesc + "|" + type.name();
        return sha256(raw);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Normalizes a transaction description for hashing.
     *
     * Steps:
     *   1. Lowercase
     *   2. Collapse all whitespace to single space
     *   3. Trim leading/trailing whitespace
     *
     * We intentionally do NOT strip UPI reference numbers or merchant-
     * specific tokens here. The hash is meant to be stable for identical
     * descriptions, not to merge semantically similar ones. Merchant
     * normalization happens separately in AIContextBuilderService.
     */
    private String normalizeDescription(String description) {
        if (description == null) return "";
        return description
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Computes SHA-256 hex of the input string (UTF-8 encoded).
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}