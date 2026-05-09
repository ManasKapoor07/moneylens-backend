package com.moneylens.service;

import com.moneylens.entity.Statement;
import com.moneylens.entity.Transaction;
import com.moneylens.entity.TransactionInsight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TransactionMapper — Categorisation + Deep Behavioural Analysis
 *
 * KEY DESIGN DECISIONS (derived from real PNB statement data):
 *
 * 1.  categorise() runs on the RAW description string, NOT on cleanMerchant() output.
 *     This is critical — cleaning strips the very keywords we match on.
 *
 * 2.  MERCHANT_CATEGORY uses contains() on lowercased raw description.
 *     Order: most specific / highest-value brands first.
 *
 * 3.  Peer-to-person UPI payments (AJAY KUMAR, VIVEK KUMAR, TANISHKA etc.)
 *     are categorised as "P2P Transfer", not "Other". This alone fixes 80%+ of
 *     the "Other" problem since most UPI debits in Indian statements are P2P.
 *
 * 4.  Large recurring P2P (e.g. JYOTI KAPOOR ₹11,800 total, MANAS ₹1,955)
 *     get flagged as "possible rent / shared expense" in insights.
 *
 * 5.  STAZY removed from KNOWN_SERVICES — ₹18,180 is not a subscription.
 *     It stays as P2P Transfer until user classifies it.
 *
 * 6.  "TPT MAR SAL DATOPIC TECHN" → Payroll Disbursement (employer paying staff).
 *
 * 7.  Subscription detection = known service + same amount ≥ 2 times.
 *     Person names with same amount ≠ subscription (flatmate splitting bills).
 *
 * 8.  cleanMerchant() is ONLY used for display labels, never for categorisation.
 */
@Component
public class TransactionMapper {

    private static final Logger log = LoggerFactory.getLogger(TransactionMapper.class);

    // ─────────────────────────────────────────────────────────────────────────
    // DATE FORMATS
    // ─────────────────────────────────────────────────────────────────────────

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy")
    );

    // ─────────────────────────────────────────────────────────────────────────
    // MERCHANT → CATEGORY  (matched on raw lowercased description)
    //
    // Rule: if description.toLowerCase().contains(key) → assign value
    // Order: specific brands BEFORE generic words
    // ─────────────────────────────────────────────────────────────────────────

    private static final LinkedHashMap<String, String> MERCHANT_CATEGORY = new LinkedHashMap<>();

    static {
        // ── Payroll / Salary Disbursement (check FIRST — high-value, must not fall through) ──
        // "TPT MAR SAL", "DATOPIC TECHN" etc. — employer paying staff salaries
        MERCHANT_CATEGORY.put("mar sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("apr sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("may sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("jun sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("jul sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("aug sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("sep sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("oct sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("nov sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("dec sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("jan sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("feb sal",        "Payroll Disbursed");
        MERCHANT_CATEGORY.put("payroll",        "Payroll Disbursed");

        // ── EMI / Loan (check early — keyword "emi" is unambiguous) ──
        MERCHANT_CATEGORY.put("emi ",           "EMI / Loan");
        MERCHANT_CATEGORY.put(" emi",           "EMI / Loan");
        MERCHANT_CATEGORY.put("loan repay",     "EMI / Loan");
        MERCHANT_CATEGORY.put("bajaj fin",      "EMI / Loan");
        MERCHANT_CATEGORY.put("hdfc loan",      "EMI / Loan");
        MERCHANT_CATEGORY.put("icici loan",     "EMI / Loan");
        MERCHANT_CATEGORY.put("equitas",        "EMI / Loan");

        // ── Food Delivery ──
        MERCHANT_CATEGORY.put("zomato",         "Food & Dining");
        MERCHANT_CATEGORY.put("swiggy",         "Food & Dining");
        MERCHANT_CATEGORY.put("eatsure",        "Food & Dining");
        MERCHANT_CATEGORY.put("faasos",         "Food & Dining");
        MERCHANT_CATEGORY.put("box8",           "Food & Dining");
        MERCHANT_CATEGORY.put("behrouz",        "Food & Dining");
        MERCHANT_CATEGORY.put("freshmenu",      "Food & Dining");
        MERCHANT_CATEGORY.put("bistro",         "Food & Dining");   // BISTRO-BLINKIT in your data

        // ── Restaurants / Cafes ──
        MERCHANT_CATEGORY.put("mcdonald",       "Food & Dining");
        MERCHANT_CATEGORY.put("dominos",        "Food & Dining");
        MERCHANT_CATEGORY.put("pizzahut",       "Food & Dining");
        MERCHANT_CATEGORY.put("pizza hut",      "Food & Dining");
        MERCHANT_CATEGORY.put("kfc",            "Food & Dining");
        MERCHANT_CATEGORY.put("subway",         "Food & Dining");
        MERCHANT_CATEGORY.put("starbucks",      "Food & Dining");
        MERCHANT_CATEGORY.put("chaayos",        "Food & Dining");
        MERCHANT_CATEGORY.put("chai point",     "Food & Dining");
        MERCHANT_CATEGORY.put("restaurant",     "Food & Dining");
        MERCHANT_CATEGORY.put("cafe",           "Food & Dining");
        MERCHANT_CATEGORY.put("dhaba",          "Food & Dining");
        MERCHANT_CATEGORY.put("bakery",         "Food & Dining");
        MERCHANT_CATEGORY.put("canteen",        "Food & Dining");

        // ── Groceries / Quick Commerce ──
        // NOTE: "zepto" MUST match "UPI-ZEPTO MARKETPLACE" and "UPI-ZEPTO-ZEPTOONLINE@YBL"
        MERCHANT_CATEGORY.put("zepto",          "Groceries");
        MERCHANT_CATEGORY.put("blinkit",        "Groceries");
        MERCHANT_CATEGORY.put("bigbasket",      "Groceries");
        MERCHANT_CATEGORY.put("big basket",     "Groceries");
        MERCHANT_CATEGORY.put("grofers",        "Groceries");
        MERCHANT_CATEGORY.put("dmart",          "Groceries");
        MERCHANT_CATEGORY.put("d-mart",         "Groceries");
        MERCHANT_CATEGORY.put("reliance fresh", "Groceries");
        MERCHANT_CATEGORY.put("reliance smart", "Groceries");
        MERCHANT_CATEGORY.put("more retail",    "Groceries");
        MERCHANT_CATEGORY.put("spencer",        "Groceries");
        MERCHANT_CATEGORY.put("milkbasket",     "Groceries");
        MERCHANT_CATEGORY.put("dunzo",          "Groceries");
        MERCHANT_CATEGORY.put("instamart",      "Groceries");
        MERCHANT_CATEGORY.put("ekart",          "Groceries");       // Flipkart delivery arm
        MERCHANT_CATEGORY.put("jiomart",        "Groceries");
        MERCHANT_CATEGORY.put("supermart",      "Groceries");

        // ── Transport ──
        // NOTE: "uber" matches "UPI-UBER INDIA SYSTEMS" ✓
        // NOTE: "rapido" matches "UPI-RAPIDO-PAYTM-76881028@PTYBL" ✓
        // NOTE: "roppen" added — appears as bike rental / auto service in your data
        MERCHANT_CATEGORY.put("uber",           "Transport");
        MERCHANT_CATEGORY.put("ola",            "Transport");
        MERCHANT_CATEGORY.put("rapido",         "Transport");
        MERCHANT_CATEGORY.put("roppen",         "Transport");       // your data: bike/auto service
        MERCHANT_CATEGORY.put("yulu",           "Transport");
        MERCHANT_CATEGORY.put("bounce",         "Transport");
        MERCHANT_CATEGORY.put("irctc",          "Transport");
        MERCHANT_CATEGORY.put("indian rail",    "Transport");
        MERCHANT_CATEGORY.put("railwire",       "Transport");
        MERCHANT_CATEGORY.put("redbus",         "Transport");
        MERCHANT_CATEGORY.put("abhibus",        "Transport");
        MERCHANT_CATEGORY.put("indigo",         "Transport");
        MERCHANT_CATEGORY.put("air india",      "Transport");
        MERCHANT_CATEGORY.put("spicejet",       "Transport");
        MERCHANT_CATEGORY.put("akasa",          "Transport");
        MERCHANT_CATEGORY.put("fastag",         "Transport");
        MERCHANT_CATEGORY.put("toll",           "Transport");
        MERCHANT_CATEGORY.put("parking",        "Transport");
        MERCHANT_CATEGORY.put("petrol",         "Fuel");
        MERCHANT_CATEGORY.put("diesel",         "Fuel");
        MERCHANT_CATEGORY.put("hp pump",        "Fuel");
        MERCHANT_CATEGORY.put("iocl",           "Fuel");
        MERCHANT_CATEGORY.put("bpcl",           "Fuel");
        MERCHANT_CATEGORY.put("hpcl",           "Fuel");
        MERCHANT_CATEGORY.put("cng",            "Fuel");

        // ── Entertainment ──
        MERCHANT_CATEGORY.put("playall",        "Entertainment");   // your data: "PLAYALL NOIDA"
        MERCHANT_CATEGORY.put("bookmyshow",     "Entertainment");
        MERCHANT_CATEGORY.put("pvr",            "Entertainment");
        MERCHANT_CATEGORY.put("inox",           "Entertainment");
        MERCHANT_CATEGORY.put("cinepolis",      "Entertainment");
        MERCHANT_CATEGORY.put("steam",          "Entertainment");
        MERCHANT_CATEGORY.put("gaming",         "Entertainment");

        // ── Streaming Subscriptions ──
        MERCHANT_CATEGORY.put("netflix",        "Subscriptions");
        MERCHANT_CATEGORY.put("hotstar",        "Subscriptions");
        MERCHANT_CATEGORY.put("disney",         "Subscriptions");
        MERCHANT_CATEGORY.put("prime video",    "Subscriptions");
        MERCHANT_CATEGORY.put("zee5",           "Subscriptions");
        MERCHANT_CATEGORY.put("sonyliv",        "Subscriptions");
        MERCHANT_CATEGORY.put("mxplayer",       "Subscriptions");
        MERCHANT_CATEGORY.put("jiocinema",      "Subscriptions");
        MERCHANT_CATEGORY.put("spotify",        "Subscriptions");
        MERCHANT_CATEGORY.put("gaana",          "Subscriptions");
        MERCHANT_CATEGORY.put("jiosaavn",       "Subscriptions");
        MERCHANT_CATEGORY.put("wynk",           "Subscriptions");
        MERCHANT_CATEGORY.put("youtube premium","Subscriptions");
        MERCHANT_CATEGORY.put("google one",     "Subscriptions");
        MERCHANT_CATEGORY.put("icloud",         "Subscriptions");
        MERCHANT_CATEGORY.put("dropbox",        "Subscriptions");
        MERCHANT_CATEGORY.put("canva",          "Subscriptions");
        MERCHANT_CATEGORY.put("grammarly",      "Subscriptions");
        MERCHANT_CATEGORY.put("notion",         "Subscriptions");
        MERCHANT_CATEGORY.put("github",         "Subscriptions");
        MERCHANT_CATEGORY.put("chatgpt",        "Subscriptions");
        MERCHANT_CATEGORY.put("openai",         "Subscriptions");

        // ── Shopping / E-commerce ──
        // NOTE: "amazon" must come AFTER "amazon pay" would not be an issue since
        // we check "amazon" and "amazon pay" is still amazon
        MERCHANT_CATEGORY.put("amazon",         "Shopping");
        MERCHANT_CATEGORY.put("flipkart",       "Shopping");
        MERCHANT_CATEGORY.put("myntra",         "Shopping");
        MERCHANT_CATEGORY.put("nykaa",          "Shopping");
        MERCHANT_CATEGORY.put("meesho",         "Shopping");
        MERCHANT_CATEGORY.put("ajio",           "Shopping");
        MERCHANT_CATEGORY.put("snapdeal",       "Shopping");
        MERCHANT_CATEGORY.put("tatacliq",       "Shopping");
        MERCHANT_CATEGORY.put("shopsy",         "Shopping");
        MERCHANT_CATEGORY.put("lenskart",       "Shopping");
        MERCHANT_CATEGORY.put("pepperfry",      "Shopping");
        MERCHANT_CATEGORY.put("urban ladder",   "Shopping");
        MERCHANT_CATEGORY.put("ikea",           "Shopping");
        MERCHANT_CATEGORY.put("firstcry",       "Shopping");
        MERCHANT_CATEGORY.put("westside",       "Shopping");
        MERCHANT_CATEGORY.put("max fashion",    "Shopping");
        MERCHANT_CATEGORY.put("shoppers stop",  "Shopping");
        MERCHANT_CATEGORY.put("croma",          "Shopping");
        MERCHANT_CATEGORY.put("vijay sales",    "Shopping");
        MERCHANT_CATEGORY.put("reliance digital","Shopping");
        MERCHANT_CATEGORY.put("visage",         "Shopping");        // "VISAGE LINES" in your data

        // ── Utilities / Bills ──
        // NOTE: "airtel" matches "UPI-AIRTEL-PAYAIR7673@PTYBL" ✓
        MERCHANT_CATEGORY.put("airtel",         "Utilities");
        MERCHANT_CATEGORY.put("jio",            "Utilities");       // careful: before other "jio*" entries
        MERCHANT_CATEGORY.put("bsnl",           "Utilities");
        MERCHANT_CATEGORY.put("vodafone",       "Utilities");
        MERCHANT_CATEGORY.put("vi ",            "Utilities");
        MERCHANT_CATEGORY.put("electricity",    "Utilities");
        MERCHANT_CATEGORY.put("bses",           "Utilities");
        MERCHANT_CATEGORY.put("bescom",         "Utilities");
        MERCHANT_CATEGORY.put("msedcl",         "Utilities");
        MERCHANT_CATEGORY.put("tpddl",          "Utilities");
        MERCHANT_CATEGORY.put("tata power",     "Utilities");
        MERCHANT_CATEGORY.put("adani elec",     "Utilities");
        MERCHANT_CATEGORY.put("broadband",      "Utilities");
        MERCHANT_CATEGORY.put("act fibernet",   "Utilities");
        MERCHANT_CATEGORY.put("hathway",        "Utilities");
        MERCHANT_CATEGORY.put("excitel",        "Utilities");
        MERCHANT_CATEGORY.put("mahanagar gas",  "Utilities");
        MERCHANT_CATEGORY.put("indraprastha",   "Utilities");
        MERCHANT_CATEGORY.put("bbps",           "Utilities");
        MERCHANT_CATEGORY.put("municipality",   "Utilities");

        // ── Healthcare ──
        MERCHANT_CATEGORY.put("curelink",       "Healthcare");      // your data
        MERCHANT_CATEGORY.put("apollo",         "Healthcare");
        MERCHANT_CATEGORY.put("practo",         "Healthcare");
        MERCHANT_CATEGORY.put("1mg",            "Healthcare");
        MERCHANT_CATEGORY.put("pharmeasy",      "Healthcare");
        MERCHANT_CATEGORY.put("netmeds",        "Healthcare");
        MERCHANT_CATEGORY.put("medplus",        "Healthcare");
        MERCHANT_CATEGORY.put("healthians",     "Healthcare");
        MERCHANT_CATEGORY.put("thyrocare",      "Healthcare");
        MERCHANT_CATEGORY.put("hospital",       "Healthcare");
        MERCHANT_CATEGORY.put("pharmacy",       "Healthcare");
        MERCHANT_CATEGORY.put("medical",        "Healthcare");
        MERCHANT_CATEGORY.put("clinic",         "Healthcare");
        MERCHANT_CATEGORY.put("diagnostic",     "Healthcare");
        MERCHANT_CATEGORY.put("cult.fit",       "Healthcare");
        MERCHANT_CATEGORY.put("cure.fit",       "Healthcare");

        // ── Education ──
        MERCHANT_CATEGORY.put("udemy",          "Education");
        MERCHANT_CATEGORY.put("coursera",       "Education");
        MERCHANT_CATEGORY.put("unacademy",      "Education");
        MERCHANT_CATEGORY.put("byju",           "Education");
        MERCHANT_CATEGORY.put("vedantu",        "Education");
        MERCHANT_CATEGORY.put("upgrad",         "Education");
        MERCHANT_CATEGORY.put("simplilearn",    "Education");
        MERCHANT_CATEGORY.put("physicswallah",  "Education");
        MERCHANT_CATEGORY.put("school fee",     "Education");
        MERCHANT_CATEGORY.put("tuition",        "Education");
        MERCHANT_CATEGORY.put("coaching",       "Education");

        // ── Investment / Insurance ──
        MERCHANT_CATEGORY.put("zerodha",        "Investment");
        MERCHANT_CATEGORY.put("groww",          "Investment");
        MERCHANT_CATEGORY.put("upstox",         "Investment");
        MERCHANT_CATEGORY.put("angel",          "Investment");
        MERCHANT_CATEGORY.put("mutual fund",    "Investment");
        MERCHANT_CATEGORY.put("sbimf",          "Investment");
        MERCHANT_CATEGORY.put("hdfcmf",         "Investment");
        MERCHANT_CATEGORY.put("nps",            "Investment");
        MERCHANT_CATEGORY.put("ppf",            "Investment");
        MERCHANT_CATEGORY.put("lic",            "Investment");
        MERCHANT_CATEGORY.put("insurance",      "Investment");
        MERCHANT_CATEGORY.put("icici pru",      "Investment");
        MERCHANT_CATEGORY.put("hdfc life",      "Investment");
        MERCHANT_CATEGORY.put("sip",            "Investment");

        // ── ATM / Cash ──
        MERCHANT_CATEGORY.put("atm",            "ATM / Cash");
        MERCHANT_CATEGORY.put("cash withdraw",  "ATM / Cash");
        MERCHANT_CATEGORY.put("cdm",            "ATM / Cash");

        // ── Rent (explicit keywords only — do NOT catch "JYOTI KAPOOR" here) ──
        MERCHANT_CATEGORY.put("nobroker",       "Rent");
        MERCHANT_CATEGORY.put("nestaway",       "Rent");
        MERCHANT_CATEGORY.put("house rent",     "Rent");
        MERCHANT_CATEGORY.put("flat rent",      "Rent");
        MERCHANT_CATEGORY.put("pg rent",        "Rent");
        MERCHANT_CATEGORY.put("rental",         "Rent");

        // ── Income signals (for CREDIT transactions) ──
        MERCHANT_CATEGORY.put("salary",         "Salary");
        MERCHANT_CATEGORY.put("stipend",        "Salary");
        MERCHANT_CATEGORY.put("interest cr",    "Interest");
        MERCHANT_CATEGORY.put("dividend",       "Dividend");
        MERCHANT_CATEGORY.put("refund",         "Refund");
        MERCHANT_CATEGORY.put("cashback",       "Refund");
        MERCHANT_CATEGORY.put("reversal",       "Refund");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KNOWN SERVICES SET
    // Used to distinguish "Subscription" from "P2P Transfer" in recurring detection.
    //
    // STAZY intentionally excluded — ₹18,180 is NOT a subscription service.
    // "paytm" included because "UPI-PANKAJ-PAYTM.S1BY2MF@PTY" is a Paytm
    // payment gateway charge, not a person named Pankaj.
    // ─────────────────────────────────────────────────────────────────────────

    private static final Set<String> KNOWN_SERVICES = Set.of(
            "zomato", "swiggy", "netflix", "hotstar", "spotify", "amazon", "flipkart",
            "airtel", "zepto", "blinkit", "bigbasket", "uber", "ola", "rapido",
            "canva", "grammarly", "notion", "zoom", "github", "google",
            "icloud", "dropbox", "ekart", "paytm", "phonepe", "gpay", "razorpay",
            "cashfree", "bharatpe",
            "curelink", "playall", "roppen", "irctc", "dominos", "mcdonald", "kfc",
            "visage", "myntra", "nykaa", "meesho", "dunzo", "grofers", "dmart",
            "bistro", "disney", "zee5", "sonyliv", "jiocinema", "bookmyshow",
            "pvr", "inox", "cinepolis", "openai", "chatgpt", "actfibernet",
            "excitel", "hathway", "bses", "bescom", "msedcl",
            "practo", "1mg", "pharmeasy", "netmeds", "healthians"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // ROW → TRANSACTION
    // ─────────────────────────────────────────────────────────────────────────

    public Transaction mapRowToTransaction(Map<String, String> row, Statement statement) {
        String dateStr    = getColumn(row, "date");
        String desc       = getColumn(row, "description", "narration", "particulars", "details", "remarks");
        String debitStr   = getColumn(row, "debit", "withdrawal", "dr", "debit amount", "withdrawal amt.", "withdrawal amt");
        String creditStr  = getColumn(row, "credit", "deposit", "cr", "credit amount", "deposit amt.", "deposit amt");
        String balanceStr = getColumn(row, "balance", "closing balance", "running balance", "closing bal");

        if (dateStr == null || desc == null) return null;

        LocalDate date = parseDate(dateStr);
        if (date == null) return null;

        BigDecimal debit   = parseAmount(debitStr);
        BigDecimal credit  = parseAmount(creditStr);
        BigDecimal balance = parseAmount(balanceStr);

        if (debit == null && credit == null) return null;

        Transaction.Type type = (debit != null && debit.compareTo(BigDecimal.ZERO) > 0)
                ? Transaction.Type.DEBIT : Transaction.Type.CREDIT;

        BigDecimal amount = (type == Transaction.Type.DEBIT) ? debit : credit;
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return null;

        // Categorise runs on the RAW description — never on cleaned output
        String category = categorise(desc, type);

        return Transaction.builder()
                .statement(statement)
                .date(date)
                .description(desc.trim())
                .amount(amount)
                .type(type)
                .balance(balance)
                .category(category)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CATEGORISE  — runs on RAW description string
    //
    // Priority order:
    //   1. EMI (unambiguous keyword)
    //   2. Known merchant dictionary
    //   3. P2P person-name heuristic (catches most UPI debits)
    //   4. Other
    // ─────────────────────────────────────────────────────────────────────────

    public String categorise(String description, Transaction.Type type) {
        if (description == null) return "Other";
        String lower = description.toLowerCase();

        // 1. EMI detection (most unambiguous — match standalone "emi")
        if (lower.contains("emi ") || lower.contains(" emi") || lower.startsWith("emi")) {
            return "EMI / Loan";
        }

        // 2. Credit-side income signals
        if (type == Transaction.Type.CREDIT) {
            if (lower.contains("salary") || lower.contains("payroll") || lower.contains("stipend")) return "Salary";
            if (lower.contains("interest"))  return "Interest";
            if (lower.contains("refund") || lower.contains("cashback") || lower.contains("reversal")) return "Refund";
            if (lower.contains("dividend"))  return "Dividend";
        }

        // 3. Merchant dictionary lookup (on raw lowercased description)
        for (Map.Entry<String, String> entry : MERCHANT_CATEGORY.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 4. P2P person-name heuristic
        //    "UPI-AJAY KUMAR", "UPI-TANISHKA", "UPI-VIVEK KUMAR", etc.
        //    These have NO brand/service keyword match, so we catch them here
        //    instead of dumping them into "Other".
        if (isLikelyP2P(lower)) {
            return "P2P Transfer";
        }

        // 5. Generic transfer signals
        if (lower.contains("neft") || lower.contains("rtgs") || lower.contains("imps")) {
            return "Bank Transfer";
        }

        return "Other";
    }

    /** Convenience overload for callers that don't have the type */
    public String categorise(String description) {
        return categorise(description, Transaction.Type.DEBIT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DEEP INSIGHT DERIVATION
    // ─────────────────────────────────────────────────────────────────────────

    public List<TransactionInsight> deriveInsights(Statement statement, List<Transaction> transactions) {

        List<TransactionInsight> out = new ArrayList<>();
        if (transactions.isEmpty()) return out;

        List<Transaction> debits  = transactions.stream().filter(t -> t.getType() == Transaction.Type.DEBIT).toList();
        List<Transaction> credits = transactions.stream().filter(t -> t.getType() == Transaction.Type.CREDIT).toList();

        BigDecimal totalDebit  = sum(debits);
        BigDecimal totalCredit = sum(credits);
        BigDecimal netFlow     = totalCredit.subtract(totalDebit);

        // Exclude internal/payroll from "real expense" total for savings rate
        BigDecimal payrollOut = sum(debits.stream()
                .filter(t -> "Payroll Disbursed".equals(t.getCategory())).toList());
        BigDecimal realExpense = totalDebit.subtract(payrollOut);

        BigDecimal savingsRate = totalCredit.compareTo(BigDecimal.ZERO) > 0
                ? netFlow.max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // ── 1. SUMMARY ──────────────────────────────────────────────────────

        out.add(ins(statement, "SUMMARY", "Total Spent",        "₹" + fmt(totalDebit),    null));
        out.add(ins(statement, "SUMMARY", "Total Received",     "₹" + fmt(totalCredit),   null));
        out.add(ins(statement, "SUMMARY", "Net Flow",           "₹" + fmt(netFlow),        null));
        out.add(ins(statement, "SUMMARY", "Total Transactions", String.valueOf(transactions.size()), null));
        out.add(ins(statement, "SUMMARY", "Savings Rate",       savingsRate + "%",
                savingsRate.compareTo(BigDecimal.valueOf(20)) < 0
                        ? "⚠ Below recommended 20%" : "✓ Healthy"));

        // ── 2. INCOME DETECTION ─────────────────────────────────────────────

        if (credits.isEmpty()) {
            out.add(ins(statement, "INCOME", "No Income Detected", "₹0.00",
                    "No salary or credit found this month"));
        } else {
            credits.stream()
                    .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                    .limit(5)
                    .forEach(t -> out.add(ins(statement, "INCOME",
                            cleanMerchant(t.getDescription()),
                            "₹" + fmt(t.getAmount()),
                            "Credited on " + t.getDate())));
        }

        // ── 3. CATEGORY BREAKDOWN (real expenses only, excludes payroll-out) ─

        Map<String, BigDecimal> byCategory = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "Other",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        BigDecimal baseForPct = realExpense.compareTo(BigDecimal.ZERO) > 0 ? realExpense : totalDebit;

        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> {
                    BigDecimal pct = baseForPct.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(baseForPct, 1, RoundingMode.HALF_UP);
                    out.add(ins(statement, "CATEGORY", e.getKey(), "₹" + fmt(e.getValue()), pct + "%"));
                });

        // ── 4. TOP MERCHANTS ────────────────────────────────────────────────

        Map<String, BigDecimal> byMerchant = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> cleanMerchant(t.getDescription()),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        byMerchant.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> out.add(ins(statement, "TOP_MERCHANT",
                        e.getKey(), "₹" + fmt(e.getValue()), null)));

        // ── 5. SUBSCRIPTION DETECTION ────────────────────────────────────────
        // Only known services + same amount ≥ 2 times = subscription.
        //
        // GROUPING KEY: serviceFingerprint(raw) + amount
        //   - serviceFingerprint extracts the brand keyword from the raw description
        //     (e.g. "zomato" from "UPI-ZOMATOFOOD-ZOMATOFOOD.PAYU@MAIRTEL")
        //   - This prevents two unrelated service transactions with the same
        //     cleanMerchant() display label from merging incorrectly.

        Map<String, List<Transaction>> recurringBuckets = debits.stream()
                .filter(t -> isKnownService(t.getDescription()))
                .collect(Collectors.groupingBy(
                        t -> serviceFingerprint(t.getDescription()) + "||" + t.getAmount().toPlainString()));

        recurringBuckets.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .forEach(e -> {
                    Transaction sample   = e.getValue().get(0);
                    BigDecimal monthly   = sample.getAmount();
                    BigDecimal annualEst = monthly.multiply(BigDecimal.valueOf(12));
                    out.add(ins(statement, "SUBSCRIPTION",
                            cleanMerchant(sample.getDescription()),
                            "₹" + fmt(monthly) + "/mo · est. ₹" + fmt(annualEst) + "/yr",
                            e.getValue().size() + " occurrences"));
                });

        // ── 6. P2P RECURRING PAYMENTS ────────────────────────────────────────
        // Person names appearing ≥ 2 times — surfaces flatmate splits, possible rent.
        //
        // GROUPING KEY: upiHandle(raw)
        //   - Extracts the UPI handle (everything between "UPI-" and the first "@")
        //     e.g. "SUMIT SINGH-Q485411649" from "UPI-SUMIT SINGH-Q485411649@YBL-..."
        //   - Two different "Sumit Singh"s with different handles → separate buckets ✓
        //   - Falls back to cleanMerchant() for non-standard descriptions.

        Map<String, List<Transaction>> p2pByHandle = debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory()))
                .collect(Collectors.groupingBy(t -> upiHandle(t.getDescription())));

        p2pByHandle.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .sorted(Comparator.comparing(
                        (Map.Entry<String, List<Transaction>> e) -> sum(e.getValue())).reversed())
                .limit(10)
                .forEach(e -> {
                    BigDecimal totalP2P = sum(e.getValue());
                    int count = e.getValue().size();
                    String meta = count + " transfers";
                    if (totalP2P.compareTo(BigDecimal.valueOf(3000)) > 0) {
                        meta += " · ⚠ Possible rent/shared expense";
                    }
                    // Display label from first transaction's clean name
                    String displayName = cleanMerchant(e.getValue().get(0).getDescription());
                    out.add(ins(statement, "P2P_TRANSFER",
                            displayName, "₹" + fmt(totalP2P), meta));
                });

        // ── 7. MONTHLY TREND ────────────────────────────────────────────────

        Map<String, BigDecimal> byMonth = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getDate().format(DateTimeFormatter.ofPattern("MMM yyyy")),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        byMonth.forEach((month, total) ->
                out.add(ins(statement, "MONTHLY_TREND", month, "₹" + fmt(total), null)));

        // ── 8. WEEKLY BREAKDOWN ─────────────────────────────────────────────

        Map<Integer, List<Transaction>> byWeek = debits.stream()
                .collect(Collectors.groupingBy(t -> weekOfMonth(t.getDate())));

        for (int w = 1; w <= 4; w++) {
            List<Transaction> wTxs    = byWeek.getOrDefault(w, List.of());
            BigDecimal wTotal         = sum(wTxs);
            BigDecimal wPct           = baseForPct.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : wTotal.multiply(BigDecimal.valueOf(100)).divide(baseForPct, 1, RoundingMode.HALF_UP);
            out.add(ins(statement, "WEEKLY_BREAKDOWN", "Week " + w,
                    "₹" + fmt(wTotal),
                    wPct + "% of monthly spend · " + wTxs.size() + " transactions"));
        }

        // ── 9. DAILY SPEND SERIES (for chart rendering) ──────────────────────

        Map<LocalDate, BigDecimal> dailyDebit = debits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        Map<LocalDate, BigDecimal> dailyCredit = credits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        TreeSet<LocalDate> allDates = new TreeSet<>();
        allDates.addAll(dailyDebit.keySet());
        allDates.addAll(dailyCredit.keySet());

        StringBuilder series = new StringBuilder("[");
        boolean first = true;
        for (LocalDate d : allDates) {
            if (!first) series.append(",");
            series.append(String.format("{\"date\":\"%s\",\"debit\":%.2f,\"credit\":%.2f}",
                    d,
                    dailyDebit.getOrDefault(d, BigDecimal.ZERO),
                    dailyCredit.getOrDefault(d, BigDecimal.ZERO)));
            first = false;
        }
        series.append("]");
        out.add(ins(statement, "DAILY_SPEND_SERIES", "Daily Chart Data", series.toString(), null));

        // ── 10. LARGEST TRANSACTIONS ─────────────────────────────────────────

        debits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .forEach(t -> out.add(ins(statement, "LARGEST_TRANSACTION",
                        cleanMerchant(t.getDescription()),
                        "₹" + fmt(t.getAmount()),
                        t.getDate().toString())));

        // ── 11. BEHAVIORAL INTELLIGENCE ─────────────────────────────────────

        long totalDays    = Math.max(allDates.size(), 1);
        BigDecimal avgDay = totalDebit.divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP);

        LocalDate peakDay = dailyDebit.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        out.add(ins(statement, "BEHAVIORAL", "Avg Daily Spend",
                "₹" + fmt(avgDay),
                peakDay != null ? "Highest on " + peakDay.format(DateTimeFormatter.ofPattern("d MMM")) : null));

        // Weekend vs weekday
        BigDecimal weekendSpend = sum(debits.stream().filter(t -> isWeekend(t.getDate())).toList());
        BigDecimal weekdaySpend = sum(debits.stream().filter(t -> !isWeekend(t.getDate())).toList());
        long wkendDays = debits.stream().filter(t -> isWeekend(t.getDate())).map(Transaction::getDate).distinct().count();
        long wkdayDays = debits.stream().filter(t -> !isWeekend(t.getDate())).map(Transaction::getDate).distinct().count();
        if (wkendDays > 0 && wkdayDays > 0) {
            BigDecimal avgWkend = weekendSpend.divide(BigDecimal.valueOf(wkendDays), 0, RoundingMode.HALF_UP);
            BigDecimal avgWkday = weekdaySpend.divide(BigDecimal.valueOf(wkdayDays), 0, RoundingMode.HALF_UP);
            if (avgWkday.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ratio = avgWkend.divide(avgWkday, 1, RoundingMode.HALF_UP);
                out.add(ins(statement, "BEHAVIORAL", "Weekend vs Weekday",
                        ratio + "x higher on weekends",
                        "Weekend avg ₹" + fmt(avgWkend) + " · Weekday avg ₹" + fmt(avgWkday)));
            }
        }

        // Micro/impulse payments < ₹200
        List<Transaction> microTxs = debits.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(200)) < 0).toList();
        BigDecimal microTotal = sum(microTxs);
        if (!microTxs.isEmpty()) {
            BigDecimal microAvg = microTotal.divide(BigDecimal.valueOf(microTxs.size()), 0, RoundingMode.HALF_UP);
            out.add(ins(statement, "BEHAVIORAL", "Small UPI Payments",
                    "₹" + fmt(microTotal),
                    microTxs.size() + " payments under ₹200 · avg ₹" + fmt(microAvg)));
        }

        // Post-salary drain (if any credit exists)
        credits.stream()
                .max(Comparator.comparing(Transaction::getAmount))
                .ifPresent(salaryTx -> {
                    LocalDate sDate = salaryTx.getDate();
                    BigDecimal drain3d = sum(debits.stream()
                            .filter(t -> !t.getDate().isBefore(sDate) && !t.getDate().isAfter(sDate.plusDays(3)))
                            .toList());
                    if (drain3d.compareTo(BigDecimal.ZERO) > 0 && totalDebit.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal drainPct = drain3d.multiply(BigDecimal.valueOf(100))
                                .divide(totalDebit, 1, RoundingMode.HALF_UP);
                        out.add(ins(statement, "BEHAVIORAL", "Post-Salary Drain",
                                "₹" + fmt(drain3d) + " in 3 days",
                                drainPct + "% of monthly spend right after salary credit"));
                    }
                });

        // Food delivery habit
        List<Transaction> foodTxs = debits.stream()
                .filter(t -> "Food & Dining".equals(t.getCategory())).toList();
        BigDecimal foodTotal = sum(foodTxs);
        if (!foodTxs.isEmpty()) {
            BigDecimal avgOrder = foodTotal.divide(BigDecimal.valueOf(foodTxs.size()), 0, RoundingMode.HALF_UP);
            out.add(ins(statement, "BEHAVIORAL", "Food Delivery Habit",
                    "₹" + fmt(foodTotal) + " · " + foodTxs.size() + " orders",
                    "Avg ₹" + fmt(avgOrder) + " · ₹" + fmt(foodTotal.multiply(BigDecimal.valueOf(12))) + " projected/yr"));
        }

        // Spending spike days (> 2× daily average)
        BigDecimal doubleAvg = avgDay.multiply(BigDecimal.valueOf(2));
        dailyDebit.entrySet().stream()
                .filter(e -> e.getValue().compareTo(doubleAvg) > 0)
                .sorted(Map.Entry.<LocalDate, BigDecimal>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    BigDecimal ratio = avgDay.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE
                            : e.getValue().divide(avgDay, 1, RoundingMode.HALF_UP);
                    out.add(ins(statement, "BEHAVIORAL", "Spending Spike",
                            "₹" + fmt(e.getValue()) + " on " + e.getKey().format(DateTimeFormatter.ofPattern("d MMM")),
                            ratio + "× your daily average"));
                });

        // ── 12. FINANCIAL HEALTH ─────────────────────────────────────────────

        // EMI burden
        BigDecimal emiTotal = sum(debits.stream()
                .filter(t -> "EMI / Loan".equals(t.getCategory())).toList());
        if (emiTotal.compareTo(BigDecimal.ZERO) > 0 && totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal emiBurden = emiTotal.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 1, RoundingMode.HALF_UP);
            String flag = emiBurden.compareTo(BigDecimal.valueOf(40)) > 0 ? "🚨 Very high" :
                    emiBurden.compareTo(BigDecimal.valueOf(30)) > 0 ? "⚠ High"       : "✓ Manageable";
            out.add(ins(statement, "FINANCIAL_HEALTH", "EMI Burden",
                    emiBurden + "% of income", flag + " · recommended below 30%"));
        }

        // Savings rate score
        String savingsFlag =
                savingsRate.compareTo(BigDecimal.valueOf(30)) >= 0 ? "🏆 Excellent saver" :
                        savingsRate.compareTo(BigDecimal.valueOf(20)) >= 0 ? "✓ On track"          :
                                savingsRate.compareTo(BigDecimal.valueOf(10)) >= 0 ? "⚠ Below target"      :
                                        "🚨 Critical — save more";
        out.add(ins(statement, "FINANCIAL_HEALTH", "Savings Rate Score", savingsRate + "%", savingsFlag));

        // Burn rate
        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal burnRate = totalDebit.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 1, RoundingMode.HALF_UP);
            out.add(ins(statement, "FINANCIAL_HEALTH", "Burn Rate",
                    burnRate + "% of income spent",
                    burnRate.compareTo(BigDecimal.valueOf(100)) > 0 ? "🚨 Spending more than earning" :
                            burnRate.compareTo(BigDecimal.valueOf(80))  > 0 ? "⚠ Very little left to save"   : "✓ Within limits"));
        }

        // ── 13. SAVING OPPORTUNITIES ─────────────────────────────────────────

        if (foodTotal.compareTo(BigDecimal.valueOf(1000)) > 0) {
            BigDecimal save     = pct(foodTotal, 40);
            BigDecimal sip5yr   = sipFV(save, 5);
            out.add(ins(statement, "SAVING_OPPORTUNITY",
                    "Cut food delivery by 40%",
                    "Save ₹" + fmt(save) + "/mo",
                    "Invested in SIP → ₹" + fmt(sip5yr) + " in 5 years"));
        }

        if (microTotal.compareTo(BigDecimal.valueOf(2000)) > 0) {
            BigDecimal save     = pct(microTotal, 50);
            BigDecimal sip10yr  = sipFV(save, 10);
            out.add(ins(statement, "SAVING_OPPORTUNITY",
                    "Reduce impulse payments under ₹200",
                    "Save ₹" + fmt(save) + "/mo",
                    microTxs.size() + " transactions · SIP → ₹" + fmt(sip10yr) + " in 10 years"));
        }

        // P2P audit — surface total P2P so user can reflect
        BigDecimal totalP2P = sum(debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory())).toList());
        if (totalP2P.compareTo(BigDecimal.valueOf(5000)) > 0) {
            out.add(ins(statement, "SAVING_OPPORTUNITY",
                    "Review peer transfers",
                    "₹" + fmt(totalP2P) + " sent via UPI to individuals",
                    "Categorise recurring ones as Rent / Savings / Shared expenses"));
        }

        if (totalCredit.compareTo(BigDecimal.ZERO) == 0) {
            out.add(ins(statement, "SAVING_OPPORTUNITY",
                    "No income detected this month",
                    "Upload a statement with salary credit",
                    "We'll calculate your exact savings potential"));
        }

        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if a UPI description looks like a person-to-person transfer.
     *
     * Heuristic: starts with "upi-" AND does NOT match any known service keyword.
     * Examples that return true:
     *   "UPI-AJAY KUMAR-9650544631@YBL-..."
     *   "UPI-TANISHKA"
     *   "UPI-SUMIT SINGH-Q485411649@YBL"
     *   "UPI-VIKKI SEN-..."
     *   "UPI-SHADMAN"
     *   "UPI-MR AKASH"
     *   "UPI-JYOTI KAPOOR-8937099992@AXL"   ← large recurring → possible rent
     *   "UPI-MUNNI BHATT WO"
     *   "UPI-STAZY-..."                     ← NOT in KNOWN_SERVICES → P2P
     */
    private boolean isLikelyP2P(String lower) {
        if (!lower.startsWith("upi")) return false;
        for (String svc : KNOWN_SERVICES) {
            if (lower.contains(svc)) return false;
        }
        return true;
    }

    /**
     * Clean raw description → readable merchant label for display ONLY.
     * NEVER use this output for categorisation.
     *
     * "UPI-ZOMATO-PAYZOMATO@HDFCBANK-HDFC0MERUP"  → "Zomato"
     * "UPI-AJAY KUMAR-9650544631-3@YBL-PUNB02051"  → "Ajay Kumar"
     * "50200031942646-TPT-MAR SAL-DATOPIC TECHN"   → "Tpt Mar Sal Datopic"
     */
    public String cleanMerchant(String raw) {
        if (raw == null) return "Unknown";
        String s = raw
                .replaceAll("(?i)upi-?",  "")
                .replaceAll("(?i)neft-?", "")
                .replaceAll("(?i)imps-?", "")
                .replaceAll("@[^\\s-]+",  "")        // remove @handle
                .replaceAll("[0-9]{5,}",  "")        // remove long numbers (account nos, tx ids)
                .replaceAll("-[A-Z0-9]{6,}", "")     // remove bank codes like -YESB0YBL
                .replaceAll("[^a-zA-Z\\s]", " ")     // keep only letters
                .replaceAll("\\s+", " ")
                .trim();

        // Take first 1-3 meaningful words, skip single-char tokens
        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        int words = 0;
        for (String p : parts) {
            if (p.length() <= 1) continue;
            if (out.length() > 0) out.append(" ");
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
            if (++words == 3) break;
        }
        return out.length() > 0 ? out.toString()
                : raw.substring(0, Math.min(raw.length(), 20)).toUpperCase();
    }

    /**
     * Extracts a stable UPI handle fingerprint from a raw description.
     * Used as the grouping key for P2P recurring detection so that two
     * different people named "Sumit Singh" with different UPI IDs
     * don't collapse into one bucket.
     *
     * "UPI-SUMIT SINGH-Q485411649@YBL-YESB0YBL" → "sumit singh-q485411649"
     * "UPI-SUMIT SINGH-Q974446228@YBL-YESB0YBL" → "sumit singh-q974446228"
     * "UPI-TANISHKA"                             → "tanishka"
     * "UPI-MR AKASH-..."                         → "mr akash"
     */
    private String upiHandle(String raw) {
        if (raw == null) return "unknown";
        String lower = raw.toLowerCase();

        // Extract the segment between "upi-" and the first "@" or end-of-string
        int upiIdx = lower.indexOf("upi-");
        if (upiIdx < 0) return cleanMerchant(raw).toLowerCase();

        String after = lower.substring(upiIdx + 4); // skip "upi-"
        // Take everything up to "@"
        int atIdx = after.indexOf('@');
        String handle = atIdx > 0 ? after.substring(0, atIdx) : after;

        // Remove trailing bank code like "-YESB0YBL" (dash + 6+ alphanum)
        handle = handle.replaceAll("-[a-z0-9]{6,}$", "").trim();

        return handle.isEmpty() ? cleanMerchant(raw).toLowerCase() : handle;
    }

    /**
     * Extracts the brand/service keyword from a raw description.
     * Used as the grouping key for subscription detection.
     *
     * "UPI-ZOMATOFOOD-ZOMATOFOOD.PAYU@MAIRTEL" → "zomato"
     * "UPI-RAPIDO-PAYTM-76881028@PTYBL"        → "rapido"
     * "UPI-UBER INDIA SYSTEMS"                  → "uber"
     */
    private String serviceFingerprint(String raw) {
        if (raw == null) return "unknown";
        String lower = raw.toLowerCase();
        for (String svc : KNOWN_SERVICES) {
            if (lower.contains(svc)) return svc;
        }
        return cleanMerchant(raw).toLowerCase();
    }

    private boolean isKnownService(String description) {
        if (description == null) return false;
        String lower = description.toLowerCase();
        for (String svc : KNOWN_SERVICES) {
            if (lower.contains(svc)) return true;
        }
        return false;
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private int weekOfMonth(LocalDate d) {
        int day = d.getDayOfMonth();
        return day <= 7 ? 1 : day <= 14 ? 2 : day <= 21 ? 3 : 4;
    }

    /** SIP future value at 12% p.a. compounded monthly */
    private BigDecimal sipFV(BigDecimal monthly, int years) {
        double r  = 0.12 / 12;
        int    n  = years * 12;
        double fv = monthly.doubleValue() * ((Math.pow(1 + r, n) - 1) / r) * (1 + r);
        return BigDecimal.valueOf(fv).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal val, int percent) {
        return val.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<Transaction> txs) {
        return txs.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fmt(BigDecimal val) {
        return val == null ? "0.00" : String.format("%,.2f", val);
    }

    private TransactionInsight ins(Statement s, String type, String label, String value, String meta) {
        return TransactionInsight.builder()
                .statement(s).type(type).label(label).value(value).meta(meta).build();
    }

    private String getColumn(Map<String, String> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey().trim().equalsIgnoreCase(key)) {
                    String v = e.getValue();
                    return (v != null && !v.isBlank()) ? v : null;
                }
            }
        }
        return null;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(cleaned, fmt); }
            catch (DateTimeParseException ignored) {}
        }
        log.warn("Could not parse date: '{}'", raw);
        return null;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.replaceAll("[₹,\\s]", "").trim();
            return cleaned.isEmpty() ? null : new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}