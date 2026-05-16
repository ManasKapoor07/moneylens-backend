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
 * TransactionMapper — maps raw row maps to Transaction entities and derives
 * rich behavioral insights.
 *
 * KEY FIX: The previous categoriser checked only short keyword snippets and
 * therefore classified almost everything as "Other".  The new version:
 *   1. Normalises the FULL description to lowercase once.
 *   2. Applies a priority-ordered keyword table that covers every merchant
 *      family seen in Axis Bank UPI descriptions (which embed the merchant
 *      name inside the UPI reference string).
 *   3. Falls back to a P2P heuristic only for UPI transfers to individuals
 *      that did NOT match any merchant keyword.
 */
@Component
public class TransactionMapper {

    private static final Logger log = LoggerFactory.getLogger(TransactionMapper.class);

    // ── Date formats tried in order ───────────────────────────────────────────
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy")
    );

    // ═════════════════════════════════════════════════════════════════════════
    // CATEGORY KEYWORD TABLE
    //
    // Rules:
    //  • Order matters — first match wins.
    //  • Each entry is { keyword_substring (lowercase), category }.
    //  • Keywords are matched against the FULL lowercased description so they
    //    work even when embedded inside a UPI reference like:
    //    "UPI/P2M/609787981155/ZOMATO LIMITED /Zomato/YES BANK LIMITED YBS"
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Ordered list of [keyword, category] pairs.
     * Uses a list (not a map) to preserve insertion order and allow
     * multiple keywords mapping to the same category.
     */
    private static final List<String[]> KEYWORD_RULES = new ArrayList<>();

    static {
        // ── 1. EMI / Loan (check early — very unambiguous) ───────────────────
        addRule("bajaj fin",        "EMI / Loan");
        addRule("hdfc loan",        "EMI / Loan");
        addRule("icici loan",       "EMI / Loan");
        addRule("loan repay",       "EMI / Loan");
        addRule("equitas",          "EMI / Loan");
        addRule("nach debit",       "EMI / Loan");
        addRule("ecs debit",        "EMI / Loan");
        addRule("mandate debit",    "EMI / Loan");
        // Generic " emi" / "emi " — keep AFTER merchant-specific ones
        addRule(" emi ",            "EMI / Loan");

        // ── 2. Food & Dining ─────────────────────────────────────────────────
        addRule("zomato",           "Food & Dining");
        addRule("swiggy",           "Food & Dining");
        addRule("eatsure",          "Food & Dining");
        addRule("faasos",           "Food & Dining");
        addRule("box8",             "Food & Dining");
        addRule("behrouz",          "Food & Dining");
        addRule("freshmenu",        "Food & Dining");
        addRule("mcdonald",         "Food & Dining");
        addRule("dominos",          "Food & Dining");   // covers "Dominospizza"
        addRule("dominospizza",     "Food & Dining");
        addRule("pizza hut",        "Food & Dining");
        addRule("pizzahut",         "Food & Dining");
        addRule("kfc",              "Food & Dining");
        addRule("subway",           "Food & Dining");
        addRule("starbucks",        "Food & Dining");
        addRule("chaayos",          "Food & Dining");
        addRule("chai point",       "Food & Dining");
        addRule("burger king",      "Food & Dining");
        addRule("barbeque",         "Food & Dining");
        addRule("restaurant",       "Food & Dining");
        addRule("bistro",           "Food & Dining");   // covers BISTRO in stmt
        addRule("dhaba",            "Food & Dining");
        addRule("bakery",           "Food & Dining");
        addRule("canteen",          "Food & Dining");
        addRule("cafe",             "Food & Dining");
        addRule("coffee",           "Food & Dining");   // covers "Coffee Makers"
        addRule("tea",              "Food & Dining");

        // ── 3. Groceries ─────────────────────────────────────────────────────
        addRule("blinkit",          "Groceries");       // covers "blinki" prefix too
        addRule("blinki",           "Groceries");       // Axis truncates to "blinki"
        addRule("zepto",            "Groceries");
        addRule("bigbasket",        "Groceries");
        addRule("big basket",       "Groceries");
        addRule("grofers",          "Groceries");
        addRule("dmart",            "Groceries");
        addRule("d-mart",           "Groceries");
        addRule("reliance fresh",   "Groceries");
        addRule("reliance smart",   "Groceries");
        addRule("more retail",      "Groceries");
        addRule("spencer",          "Groceries");
        addRule("milkbasket",       "Groceries");
        addRule("dunzo",            "Groceries");
        addRule("instamart",        "Groceries");
        addRule("jiomart",          "Groceries");
        addRule("supermart",        "Groceries");
        addRule("daily basket",     "Groceries");
        addRule("grocery",          "Groceries");

        // ── 4. Shopping ──────────────────────────────────────────────────────
        addRule("amazon",           "Shopping");
        addRule("flipkart",         "Shopping");
        addRule("myntra",           "Shopping");
        addRule("nykaa",            "Shopping");
        addRule("meesho",           "Shopping");
        addRule("ajio",             "Shopping");
        addRule("snapdeal",         "Shopping");
        addRule("tatacliq",         "Shopping");
        addRule("shopsy",           "Shopping");
        addRule("lenskart",         "Shopping");
        addRule("pepperfry",        "Shopping");
        addRule("urban ladder",     "Shopping");
        addRule("ikea",             "Shopping");
        addRule("firstcry",         "Shopping");
        addRule("westside",         "Shopping");
        addRule("max fashion",      "Shopping");
        addRule("shoppers stop",    "Shopping");
        addRule("croma",            "Shopping");
        addRule("vijay sales",      "Shopping");
        addRule("reliance digital", "Shopping");
        addRule("miniso",           "Shopping");        // covers "Miniso Lulu"
        addRule("ekart",            "Shopping");
        addRule("delhivery",        "Shopping");        // logistics → shopping delivery
        addRule("marketplace pri",  "Shopping");        // covers "ZEPTO MARKETPLACE PRI"
        addRule("culinary brands",  "Shopping");        // "Culinary Brands India/Amazon RBL"
        addRule("urbancompany",     "Shopping");        // home services but treat as shopping
        addRule("urban company",    "Shopping");

        // ── 5. Transport ─────────────────────────────────────────────────────
        addRule("uber",             "Transport");
        addRule("ola ",             "Transport");       // trailing space avoids "ola" in "cola"
        addRule("rapido",           "Transport");
        addRule("roppen",           "Transport");
        addRule("yulu",             "Transport");
        addRule("bounce",           "Transport");
        addRule("irctc",            "Transport");
        addRule("indian rail",      "Transport");
        addRule("railwire",         "Transport");
        addRule("redbus",           "Transport");
        addRule("abhibus",          "Transport");
        addRule("indigo",           "Transport");       // covers "INDIGO AIRLINE"
        addRule("indigo airline",   "Transport");
        addRule("air india",        "Transport");
        addRule("spicejet",         "Transport");
        addRule("akasa",            "Transport");
        addRule("goair",            "Transport");
        addRule("vistara",          "Transport");
        addRule("fastag",           "Transport");
        addRule("filling station",  "Transport");       // "L N B FILLING STATION"
        addRule("petrol pump",      "Transport");
        addRule("parking",          "Transport");

        // ── 6. Fuel ──────────────────────────────────────────────────────────
        addRule("petrol",           "Fuel");
        addRule("diesel",           "Fuel");
        addRule("iocl",             "Fuel");
        addRule("bpcl",             "Fuel");
        addRule("hpcl",             "Fuel");
        addRule("hp pump",          "Fuel");
        addRule("cng",              "Fuel");

        // ── 7. Utilities / Bills ─────────────────────────────────────────────
        addRule("airtel",           "Utilities");       // covers "AIRTEL PAYMENTS BANK" recharge
        addRule("jio",              "Utilities");
        addRule("bsnl",             "Utilities");
        addRule("vodafone",         "Utilities");
        addRule(" vi ",             "Utilities");
        addRule("electricity",      "Utilities");
        addRule("bses",             "Utilities");
        addRule("bescom",           "Utilities");
        addRule("msedcl",           "Utilities");
        addRule("tpddl",            "Utilities");
        addRule("tata power",       "Utilities");
        addRule("adani elec",       "Utilities");
        addRule("broadband",        "Utilities");
        addRule("act fibernet",     "Utilities");
        addRule("hathway",          "Utilities");
        addRule("excitel",          "Utilities");
        addRule("mahanagar gas",    "Utilities");
        addRule("indraprastha",     "Utilities");
        addRule("bbps",             "Utilities");
        addRule("municipality",     "Utilities");
        addRule("water bill",       "Utilities");
        addRule("gas bill",         "Utilities");
        addRule("recharge",         "Utilities");

        // ── 8. Subscriptions ─────────────────────────────────────────────────
        addRule("netflix",          "Subscriptions");
        addRule("hotstar",          "Subscriptions");
        addRule("disney",           "Subscriptions");
        addRule("prime video",      "Subscriptions");
        addRule("zee5",             "Subscriptions");
        addRule("sonyliv",          "Subscriptions");
        addRule("mxplayer",         "Subscriptions");
        addRule("jiocinema",        "Subscriptions");
        addRule("spotify",          "Subscriptions");
        addRule("gaana",            "Subscriptions");
        addRule("jiosaavn",         "Subscriptions");
        addRule("wynk",             "Subscriptions");
        addRule("youtube premium",  "Subscriptions");
        addRule("google one",       "Subscriptions");
        addRule("icloud",           "Subscriptions");
        addRule("dropbox",          "Subscriptions");
        addRule("canva",            "Subscriptions");
        addRule("grammarly",        "Subscriptions");
        addRule("notion",           "Subscriptions");
        addRule("github",           "Subscriptions");
        addRule("chatgpt",          "Subscriptions");
        addRule("openai",           "Subscriptions");
        addRule("microsoft 365",    "Subscriptions");
        addRule("office 365",       "Subscriptions");
        addRule("adobe",            "Subscriptions");
        addRule("figma",            "Subscriptions");
        addRule("apple med",        "Subscriptions");  // "APPLE MED" = Apple One / iCloud+
        addRule("apple",            "Subscriptions");

        // ── 9. Entertainment ─────────────────────────────────────────────────
        addRule("bookmyshow",       "Entertainment");
        addRule("pvr",              "Entertainment");
        addRule("inox",             "Entertainment");
        addRule("cinepolis",        "Entertainment");
        addRule("steam",            "Entertainment");
        addRule("playstation",      "Entertainment");
        addRule("xbox",             "Entertainment");
        addRule("gaming",           "Entertainment");
        addRule("playgames",        "Entertainment");

        // ── 10. Healthcare ────────────────────────────────────────────────────
        addRule("apollo",           "Healthcare");
        addRule("practo",           "Healthcare");
        addRule("1mg",              "Healthcare");
        addRule("pharmeasy",        "Healthcare");
        addRule("netmeds",          "Healthcare");
        addRule("medplus",          "Healthcare");
        addRule("healthians",       "Healthcare");
        addRule("thyrocare",        "Healthcare");
        addRule("hospital",         "Healthcare");
        addRule("pharmacy",         "Healthcare");
        addRule("medical",          "Healthcare");
        addRule("diagnostic",       "Healthcare");
        addRule("ashok pharma",     "Healthcare");     // "ASHOK PHARMA" in statement
        addRule("pharma",           "Healthcare");
        addRule("clinic",           "Healthcare");
        addRule("cult.fit",         "Healthcare");
        addRule("cure.fit",         "Healthcare");
        addRule("curefit",          "Healthcare");
        addRule("doctor",           "Healthcare");
        addRule("lab test",         "Healthcare");

        // ── 11. Investment ────────────────────────────────────────────────────
        addRule("zerodha",          "Investment");      // covers "ICCL ZERODHA"
        addRule("iccl",             "Investment");      // ICCL = NSE clearing (Zerodha)
        addRule("groww",            "Investment");
        addRule("upstox",           "Investment");
        addRule("angel",            "Investment");
        addRule("mutual fund",      "Investment");
        addRule("sbimf",            "Investment");
        addRule("hdfcmf",           "Investment");
        addRule("nps",              "Investment");
        addRule("ppf",              "Investment");
        addRule("lic ",             "Investment");
        addRule("insurance",        "Investment");
        addRule("icici pru",        "Investment");
        addRule("hdfc life",        "Investment");
        addRule("sip",              "Investment");
        addRule("gold bond",        "Investment");
        addRule("smallcase",        "Investment");
        addRule("coin by",          "Investment");

        // ── 12. Education ─────────────────────────────────────────────────────
        addRule("udemy",            "Education");
        addRule("coursera",         "Education");
        addRule("unacademy",        "Education");
        addRule("byju",             "Education");
        addRule("vedantu",          "Education");
        addRule("upgrad",           "Education");
        addRule("simplilearn",      "Education");
        addRule("physicswallah",    "Education");
        addRule("school fee",       "Education");
        addRule("school/trf",       "Education");      // "B R INTERNATIONAL PUBLIC SCHOOL/trf"
        addRule("public school",    "Education");
        addRule("tuition",          "Education");
        addRule("coaching",         "Education");
        addRule("college fee",      "Education");
        addRule("university",       "Education");

        // ── 13. Rent ──────────────────────────────────────────────────────────
        addRule("nobroker",         "Rent");
        addRule("nestaway",         "Rent");
        addRule("house rent",       "Rent");
        addRule("flat rent",        "Rent");
        addRule("pg rent",          "Rent");
        addRule("rental",           "Rent");
        addRule("magicbricks",      "Rent");
        addRule("99acres",          "Rent");

        // ── 14. ATM / Cash Withdrawal ─────────────────────────────────────────
        addRule("cash withdraw",    "Cash Withdrawal");
        addRule("atm ",             "Cash Withdrawal");
        addRule("cdm",              "Cash Withdrawal");
        addRule("cwdr",             "Cash Withdrawal");

        // ── 15. Credit-side categories (for CREDIT transactions) ──────────────
        // These are handled in categorise(desc, type) below, not here.
        // Listing them here would misfire on debit side.

        // ── 16. Tax / Government ──────────────────────────────────────────────
        addRule("income tax",       "Tax");
        addRule("gst",              "Tax");
        addRule("tds",              "Tax");
        addRule("epfo",             "Tax");
        addRule("pf ",              "Tax");

        // ── 17. Personal Care ─────────────────────────────────────────────────
        addRule("salon",            "Personal Care");
        addRule("haircut",          "Personal Care");
        addRule("spa",              "Personal Care");
        addRule("beauty",           "Personal Care");
        addRule("nails",            "Personal Care");

        // ── 18. Charitable / Religious ────────────────────────────────────────
        addRule("temple",           "Charitable");
        addRule("donation",         "Charitable");
        addRule("charity",          "Charitable");
        addRule("ngo",              "Charitable");
    }

    private static void addRule(String keyword, String category) {
        KEYWORD_RULES.add(new String[]{keyword.toLowerCase(), category});
    }

    // ── Set of known commercial services for P2P detection ───────────────────
    private static final Set<String> KNOWN_SERVICES = new HashSet<>(Arrays.asList(
            "zomato","swiggy","netflix","hotstar","spotify","amazon","flipkart",
            "airtel","zepto","blinkit","blinki","bigbasket","uber","ola","rapido",
            "canva","grammarly","notion","zoom","github","google","icloud","dropbox",
            "paytm","phonepe","razorpay","cashfree","bharatpe","curefit","bookmyshow",
            "pvr","inox","dominos","mcdonald","kfc","visage","myntra","nykaa","meesho",
            "dunzo","grofers","dmart","bistro","disney","zee5","sonyliv","jiocinema",
            "practo","1mg","pharmeasy","netmeds","zerodha","groww","upstox","angel",
            "byju","unacademy","udemy","indigo","spicejet","irctc","redbus","abhibus",
            "swiggy","eatsure","freshmenu","openai","chatgpt","apple","microsoft",
            "actfibernet","excitel","hathway","urbancompany","urban company","miniso",
            "delhivery","ekart","marketplace","filling station","culinary","starbucks",
            "ashok pharma","coffee","iccl","lulu","urbancomp"
    ));

    // ═════════════════════════════════════════════════════════════════════════
    // mapRowToTransaction
    // ═════════════════════════════════════════════════════════════════════════

    public Transaction mapRowToTransaction(Map<String, String> row, Statement statement) {

        String dateStr    = getCol(row, "date");
        String desc       = getCol(row, "description","narration","particulars","details","remarks");
        String debitStr   = getCol(row, "debit","withdrawal","dr","debit amount","withdrawal amt.","withdrawal amt");
        String creditStr  = getCol(row, "credit","deposit","cr","credit amount","deposit amt.","deposit amt");
        String balanceStr = getCol(row, "balance","closing balance","running balance","closing bal");

        if (dateStr == null || desc == null) {
            log.debug("Skip row — missing date or desc: {}", row);
            return null;
        }

        LocalDate date = parseDate(dateStr);
        if (date == null) return null;

        BigDecimal debit   = parseAmount(debitStr);
        BigDecimal credit  = parseAmount(creditStr);
        BigDecimal balance = parseAmount(balanceStr);

        if (debit  != null && debit.compareTo(BigDecimal.ZERO)  == 0) debit  = null;
        if (credit != null && credit.compareTo(BigDecimal.ZERO) == 0) credit = null;
        if (debit == null && credit == null) {
            log.debug("Skip — no non-zero amount: {}", row);
            return null;
        }

        Transaction.Type type;
        BigDecimal amount;

        if (debit != null && credit == null) {
            type = Transaction.Type.DEBIT;   amount = debit;
        } else if (credit != null && debit == null) {
            type = Transaction.Type.CREDIT;  amount = credit;
        } else {
            // Both columns filled — prefer balance-delta context if we had it
            // (parser already resolved this; fall back to description)
            boolean creditSig = isCreditKeyword(desc);
            type   = creditSig ? Transaction.Type.CREDIT : Transaction.Type.DEBIT;
            amount = creditSig ? credit : debit;
        }

        String category = categorise(desc, type);

        log.debug("TX {} | {} | {} | ₹{} | {}",
                date, type, category, amount,
                desc.substring(0, Math.min(desc.length(), 60)));

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

    // ═════════════════════════════════════════════════════════════════════════
    // CATEGORISE — main public entry point
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Categorises a transaction description.
     *
     * Strategy (in order):
     *   1. Credit-side income signals (salary, refund, interest, etc.)
     *   2. Keyword table (KEYWORD_RULES) — first match wins
     *   3. P2P heuristic for UPI transfers to individuals
     *   4. Generic NEFT/RTGS/IMPS → "Bank Transfer"
     *   5. Fallback → "Other"
     */
    public String categorise(String description, Transaction.Type type) {
        if (description == null || description.isBlank()) return "Other";
        String lo = description.toLowerCase();

        // ── Step 1: Credit-side income ────────────────────────────────────────
        if (type == Transaction.Type.CREDIT) {
            if (lo.contains("salary")  || lo.contains("payroll") || lo.contains("stipend"))
                return "Salary";
            if (lo.contains("interest") && !lo.contains("loan"))
                return "Interest";
            if (lo.contains("refund") || lo.contains("cashback") || lo.contains("reversal"))
                return "Refund";
            if (lo.contains("dividend"))
                return "Dividend";
            // School / institution credit → Income (not "Education")
            if (lo.contains("school") || lo.contains("college") || lo.contains("university"))
                return "Income";
        }

        // ── Step 2: Merchant keyword table ───────────────────────────────────
        for (String[] rule : KEYWORD_RULES) {
            if (lo.contains(rule[0])) return rule[1];
        }

        // ── Step 3: P2P transfer heuristic ───────────────────────────────────
        // A UPI P2P has the pattern UPI/P2A/... (person-to-account) or
        // UPI/P2P and none of the known service keywords matched above.
        if (isP2P(lo)) return "P2P Transfer";

        // ── Step 4: Generic bank transfer ────────────────────────────────────
        if (lo.contains("neft") || lo.contains("rtgs") || lo.contains("imps")
                || lo.contains("trf") || lo.contains("transfer"))
            return "Bank Transfer";

        // ── Step 5: Catch HDFC collection charges (Zomato routes through HDFC)
        // "/Collec/HDFC BANK LTD" appears repeatedly in this statement.
        // These are merchant collections — already caught by "zomato" above if
        // "zomato" is anywhere in the description.  If it reaches here it means
        // the merchant name was stripped by PDF parser — label it "Merchant Payment".
        if (lo.contains("collec") && lo.contains("hdfc"))
            return "Merchant Payment";

        return "Other";
    }

    public String categorise(String description) {
        return categorise(description, Transaction.Type.DEBIT);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // deriveInsights — full behavioral insight suite
    // ═════════════════════════════════════════════════════════════════════════

    public List<TransactionInsight> deriveInsights(Statement statement, List<Transaction> txns) {

        List<TransactionInsight> out = new ArrayList<>();
        if (txns.isEmpty()) {
            out.add(ins(statement, "SUMMARY", "No Transactions", "0", "Parser found 0 transactions"));
            return out;
        }

        List<Transaction> debits  = txns.stream().filter(t -> t.getType() == Transaction.Type.DEBIT).toList();
        List<Transaction> credits = txns.stream().filter(t -> t.getType() == Transaction.Type.CREDIT).toList();

        BigDecimal totalDebit  = sum(debits);
        BigDecimal totalCredit = sum(credits);
        BigDecimal netFlow     = totalCredit.subtract(totalDebit);

        BigDecimal savingsRate = totalCredit.compareTo(BigDecimal.ZERO) > 0
                ? netFlow.max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(100))
                .divide(totalCredit, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Daily aggregates
        Map<LocalDate, BigDecimal> dailyDebit = debits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        Map<LocalDate, BigDecimal> dailyCredit = credits.stream()
                .collect(Collectors.groupingBy(Transaction::getDate,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        TreeSet<LocalDate> allDates = new TreeSet<>();
        allDates.addAll(dailyDebit.keySet());
        allDates.addAll(dailyCredit.keySet());

        long totalDays = Math.max(allDates.size(), 1);
        BigDecimal avgDay = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : totalDebit.divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.HALF_UP);

        // ── 1. SUMMARY ────────────────────────────────────────────────────────
        out.add(ins(statement, "SUMMARY", "Total Spent",        "₹" + fmt(totalDebit),  null));
        out.add(ins(statement, "SUMMARY", "Total Received",     "₹" + fmt(totalCredit), null));
        out.add(ins(statement, "SUMMARY", "Net Flow",           "₹" + fmt(netFlow),     null));
        out.add(ins(statement, "SUMMARY", "Total Transactions", String.valueOf(txns.size()), null));
        out.add(ins(statement, "SUMMARY", "Debit Count",        String.valueOf(debits.size()),  null));
        out.add(ins(statement, "SUMMARY", "Credit Count",       String.valueOf(credits.size()), null));
        out.add(ins(statement, "SUMMARY", "Savings Rate",       savingsRate + "%",
                savingsRate.compareTo(BigDecimal.valueOf(20)) < 0 ? "⚠ Below recommended 20%" : "✓ Healthy"));

        // ── 2. INCOME ─────────────────────────────────────────────────────────
        if (credits.isEmpty()) {
            out.add(ins(statement, "INCOME", "No Income Detected", "₹0.00",
                    "No credit found in this period"));
        } else {
            credits.stream()
                    .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                    .limit(5)
                    .forEach(t -> out.add(ins(statement, "INCOME",
                            cleanMerchant(t.getDescription()),
                            "₹" + fmt(t.getAmount()),
                            "Credited on " + t.getDate())));
        }

        // ── 3. CATEGORY BREAKDOWN ─────────────────────────────────────────────
        Map<String, BigDecimal> byCategory = debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "Other",
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> {
                    BigDecimal pct = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(totalDebit, 1, RoundingMode.HALF_UP);
                    out.add(ins(statement, "CATEGORY", e.getKey(),
                            "₹" + fmt(e.getValue()), pct + "%"));
                });

        // ── 4. TOP MERCHANTS ──────────────────────────────────────────────────
        debits.stream()
                .collect(Collectors.groupingBy(
                        t -> cleanMerchant(t.getDescription()),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> out.add(ins(statement, "TOP_MERCHANT",
                        e.getKey(), "₹" + fmt(e.getValue()), null)));

        // ── 5. SUBSCRIPTION DETECTION ─────────────────────────────────────────
        debits.stream()
                .filter(t -> "Subscriptions".equals(t.getCategory()))
                .collect(Collectors.groupingBy(
                        t -> cleanMerchant(t.getDescription()) + "||" + t.getAmount().toPlainString()))
                .entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .forEach(e -> {
                    Transaction s     = e.getValue().get(0);
                    BigDecimal monthly = s.getAmount();
                    out.add(ins(statement, "SUBSCRIPTION",
                            cleanMerchant(s.getDescription()),
                            "₹" + fmt(monthly) + "/mo",
                            e.getValue().size() + " occurrences · est. ₹"
                                    + fmt(monthly.multiply(BigDecimal.valueOf(12))) + "/yr"));
                });

        // ── 6. P2P RECURRING ─────────────────────────────────────────────────
        debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory()))
                .collect(Collectors.groupingBy(t -> cleanMerchant(t.getDescription())))
                .entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .sorted(Comparator.comparing(
                        (Map.Entry<String, List<Transaction>> e) -> sum(e.getValue())).reversed())
                .limit(10)
                .forEach(e -> {
                    BigDecimal total = sum(e.getValue());
                    String meta = e.getValue().size() + " transfers";
                    if (total.compareTo(BigDecimal.valueOf(3000)) > 0)
                        meta += " · ⚠ Possible rent/shared expense";
                    out.add(ins(statement, "P2P_TRANSFER",
                            e.getKey(), "₹" + fmt(total), meta));
                });

        // ── 7. MONTHLY TREND ─────────────────────────────────────────────────
        debits.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getDate().format(DateTimeFormatter.ofPattern("MMM yyyy")),
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)))
                .forEach((month, total) ->
                        out.add(ins(statement, "MONTHLY_TREND", month, "₹" + fmt(total), null)));

        // ── 8. WEEKLY BREAKDOWN ───────────────────────────────────────────────
        Map<Integer, List<Transaction>> byWeek = debits.stream()
                .collect(Collectors.groupingBy(t -> weekOfMonth(t.getDate())));
        for (int w = 1; w <= 4; w++) {
            List<Transaction> wTxs = byWeek.getOrDefault(w, List.of());
            BigDecimal wTotal = sum(wTxs);
            BigDecimal wPct   = totalDebit.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : wTotal.multiply(BigDecimal.valueOf(100)).divide(totalDebit, 1, RoundingMode.HALF_UP);
            out.add(ins(statement, "WEEKLY_BREAKDOWN", "Week " + w,
                    "₹" + fmt(wTotal),
                    wPct + "% of monthly spend · " + wTxs.size() + " transactions"));
        }

        // ── 9. DAILY SPEND SERIES ─────────────────────────────────────────────
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

        // ── 10. LARGEST TRANSACTIONS ──────────────────────────────────────────
        debits.stream()
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .forEach(t -> out.add(ins(statement, "LARGEST_TRANSACTION",
                        cleanMerchant(t.getDescription()),
                        "₹" + fmt(t.getAmount()),
                        t.getDate().toString())));

        // ── 11. BEHAVIORAL INTELLIGENCE ───────────────────────────────────────

        // Average daily spend
        LocalDate peakDay = dailyDebit.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        out.add(ins(statement, "BEHAVIORAL", "Avg Daily Spend", "₹" + fmt(avgDay),
                peakDay != null ? "Highest on "
                        + peakDay.format(DateTimeFormatter.ofPattern("d MMM")) : null));

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

        // Micro / impulse UPI payments < ₹200
        List<Transaction> micro = debits.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(200)) < 0).toList();
        BigDecimal microTotal = sum(micro);
        if (!micro.isEmpty()) {
            BigDecimal microAvg = microTotal.divide(BigDecimal.valueOf(micro.size()), 0, RoundingMode.HALF_UP);
            out.add(ins(statement, "BEHAVIORAL", "Small UPI Payments", "₹" + fmt(microTotal),
                    micro.size() + " payments under ₹200 · avg ₹" + fmt(microAvg)));
        }

        // Post-salary drain (3 days after largest credit)
        credits.stream().max(Comparator.comparing(Transaction::getAmount)).ifPresent(salary -> {
            LocalDate sd = salary.getDate();
            BigDecimal drain = sum(debits.stream()
                    .filter(t -> !t.getDate().isBefore(sd) && !t.getDate().isAfter(sd.plusDays(3)))
                    .toList());
            if (drain.compareTo(BigDecimal.ZERO) > 0 && totalDebit.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = drain.multiply(BigDecimal.valueOf(100))
                        .divide(totalDebit, 1, RoundingMode.HALF_UP);
                out.add(ins(statement, "BEHAVIORAL", "Post-Salary Drain",
                        "₹" + fmt(drain) + " in 3 days",
                        pct + "% of monthly spend right after salary credit"));
            }
        });

        // Food delivery habit
        List<Transaction> food = debits.stream()
                .filter(t -> "Food & Dining".equals(t.getCategory())).toList();
        BigDecimal foodTotal = sum(food);
        if (!food.isEmpty()) {
            BigDecimal avgOrder = foodTotal.divide(BigDecimal.valueOf(food.size()), 0, RoundingMode.HALF_UP);
            out.add(ins(statement, "BEHAVIORAL", "Food Delivery Habit",
                    "₹" + fmt(foodTotal) + " · " + food.size() + " orders",
                    "Avg ₹" + fmt(avgOrder) + " · ₹"
                            + fmt(foodTotal.multiply(BigDecimal.valueOf(12))) + " projected/yr"));
        }

        // Spending spikes (days that are 2× the average)
        BigDecimal doubleAvg = avgDay.multiply(BigDecimal.valueOf(2));
        if (doubleAvg.compareTo(BigDecimal.ZERO) > 0) {
            dailyDebit.entrySet().stream()
                    .filter(e -> e.getValue().compareTo(doubleAvg) > 0)
                    .sorted(Map.Entry.<LocalDate, BigDecimal>comparingByValue().reversed())
                    .limit(3)
                    .forEach(e -> {
                        BigDecimal ratio = avgDay.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE
                                : e.getValue().divide(avgDay, 1, RoundingMode.HALF_UP);
                        out.add(ins(statement, "BEHAVIORAL", "Spending Spike",
                                "₹" + fmt(e.getValue()) + " on "
                                        + e.getKey().format(DateTimeFormatter.ofPattern("d MMM")),
                                ratio + "× your daily average"));
                    });
        }

        // Shopping frequency
        List<Transaction> shopping = debits.stream()
                .filter(t -> "Shopping".equals(t.getCategory())).toList();
        if (!shopping.isEmpty()) {
            BigDecimal shopTotal = sum(shopping);
            out.add(ins(statement, "BEHAVIORAL", "Online Shopping",
                    "₹" + fmt(shopTotal) + " · " + shopping.size() + " orders",
                    "Avg ₹" + fmt(shopTotal.divide(BigDecimal.valueOf(shopping.size()), 0, RoundingMode.HALF_UP)) + " per order"));
        }

        // ── 12. FINANCIAL HEALTH ──────────────────────────────────────────────
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

        String savingsFlag =
                savingsRate.compareTo(BigDecimal.valueOf(30)) >= 0 ? "🏆 Excellent saver" :
                        savingsRate.compareTo(BigDecimal.valueOf(20)) >= 0 ? "✓ On track"          :
                                savingsRate.compareTo(BigDecimal.valueOf(10)) >= 0 ? "⚠ Below target"      : "🚨 Critical";
        out.add(ins(statement, "FINANCIAL_HEALTH", "Savings Rate Score", savingsRate + "%", savingsFlag));

        if (totalCredit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal burnRate = totalDebit.multiply(BigDecimal.valueOf(100))
                    .divide(totalCredit, 1, RoundingMode.HALF_UP);
            out.add(ins(statement, "FINANCIAL_HEALTH", "Burn Rate",
                    burnRate + "% of income spent",
                    burnRate.compareTo(BigDecimal.valueOf(100)) > 0 ? "🚨 Spending more than earning" :
                            burnRate.compareTo(BigDecimal.valueOf(80))  > 0 ? "⚠ Very little left to save"   : "✓ Within limits"));
        }

        // ── 13. SAVING OPPORTUNITIES ──────────────────────────────────────────
        if (foodTotal.compareTo(BigDecimal.valueOf(500)) > 0) {
            BigDecimal save = pct40(foodTotal);
            out.add(ins(statement, "SAVING_OPPORTUNITY", "Cut food delivery by 40%",
                    "Save ₹" + fmt(save) + "/mo",
                    "Invested in SIP → ₹" + fmt(sipFV(save, 5)) + " in 5 years"));
        }
        if (microTotal.compareTo(BigDecimal.valueOf(1000)) > 0) {
            BigDecimal save = pct50(microTotal);
            out.add(ins(statement, "SAVING_OPPORTUNITY", "Reduce impulse payments under ₹200",
                    "Save ₹" + fmt(save) + "/mo",
                    micro.size() + " transactions · SIP → ₹" + fmt(sipFV(save, 10)) + " in 10 years"));
        }
        BigDecimal p2pTotal = sum(debits.stream()
                .filter(t -> "P2P Transfer".equals(t.getCategory())).toList());
        if (p2pTotal.compareTo(BigDecimal.valueOf(3000)) > 0) {
            out.add(ins(statement, "SAVING_OPPORTUNITY", "Review peer transfers",
                    "₹" + fmt(p2pTotal) + " sent to individuals",
                    "Categorise recurring ones as Rent / Savings / Shared expenses"));
        }

        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Returns true if description looks like a UPI P2P transfer to a person */
    private boolean isP2P(String lower) {
        if (!lower.contains("upi")) return false;
        for (String svc : KNOWN_SERVICES) {
            if (lower.contains(svc)) return false;
        }
        // Has person name pattern (P2A = person-to-account) or typical individual UPI
        return lower.contains("p2a") || lower.contains("p2p")
                || lower.matches(".*upi.*/[a-z]{2,}@.*");
    }

    /** Returns true if description contains credit-side signal words */
    private boolean isCreditKeyword(String desc) {
        if (desc == null) return false;
        String lo = desc.toLowerCase();
        return lo.contains("salary")   || lo.contains("stipend")  ||
                lo.contains("neft cr")  || lo.contains("/cr/")     ||
                lo.contains("credit")   || lo.contains("refund")   ||
                lo.contains("cashback") || lo.contains("reversal") ||
                lo.contains("interest") || lo.contains("dividend") ||
                lo.contains("deposit")  || lo.contains("inward")   ||
                lo.contains("received") || lo.contains("payroll")  ||
                (lo.contains("school") && lo.contains("trf"));
    }

    /**
     * Produces a short clean merchant name from a raw UPI description.
     * E.g. "UPI/P2M/609787981155/ZOMATO LIMITED /Zomato/YES BANK LIMITED YBS"
     *   → "Zomato Limited"
     */
    public String cleanMerchant(String raw) {
        if (raw == null) return "Unknown";

        // 1. Remove UPI reference number noise
        String s = raw
                .replaceAll("(?i)UPI/P2[AMP]/\\d+/", " ")   // UPI/P2A|P2M|P2P/<refnum>/
                .replaceAll("(?i)UPI/P2[AMP]/",        " ")   // without refnum
                .replaceAll("(?i)/UPIInt/",            " ")
                .replaceAll("(?i)/Sent u/",            " ")
                .replaceAll("(?i)/Pay to/",            " ")
                .replaceAll("(?i)/Pay To/",            " ")
                .replaceAll("(?i)/Payvia/",            " ")
                .replaceAll("(?i)/Paymen/",            " ")
                .replaceAll("(?i)/Verifi/",            " ")
                .replaceAll("(?i)/Collec/",            " ")
                .replaceAll("(?i)/Blinki/",            " ")
                .replaceAll("(?i)/Zomato/",            " Zomato ")
                .replaceAll("(?i)/I0PaNu/",            " ")
                .replaceAll("(?i)TRF/",                " ")
                .replaceAll("@[^\\s/]+",               " ")   // @upihandle
                .replaceAll("/[A-Z]{2,4} BANK.*",      " ")   // /HDFC BANK LTD YBS
                .replaceAll("/[A-Z]{2,}$",             " ")   // trailing /AXIS etc.
                .replaceAll("\\b\\d{6,}\\b",           " ")   // 6+ digit reference numbers
                .replaceAll("[^a-zA-Z\\s]",            " ")   // keep only letters + space
                .replaceAll("\\s+",                    " ")
                .trim();

        // 2. Title-case and take first 3 meaningful words
        String[] parts = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        int words = 0;
        for (String p : parts) {
            if (p.length() <= 1) continue;
            if (out.length() > 0) out.append(" ");
            out.append(Character.toUpperCase(p.charAt(0)))
                    .append(p.substring(1).toLowerCase());
            if (++words == 3) break;
        }
        return out.length() > 0 ? out.toString()
                : raw.substring(0, Math.min(raw.length(), 25));
    }

    private boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private int weekOfMonth(LocalDate d) {
        int day = d.getDayOfMonth();
        return day <= 7 ? 1 : day <= 14 ? 2 : day <= 21 ? 3 : 4;
    }

    private BigDecimal sipFV(BigDecimal monthly, int years) {
        double r  = 0.12 / 12;
        int    n  = years * 12;
        double fv = monthly.doubleValue() * ((Math.pow(1 + r, n) - 1) / r) * (1 + r);
        return BigDecimal.valueOf(fv).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal pct40(BigDecimal v) {
        return v.multiply(BigDecimal.valueOf(40)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal pct50(BigDecimal v) {
        return v.multiply(BigDecimal.valueOf(50)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<Transaction> txs) {
        return txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fmt(BigDecimal v) {
        return v == null ? "0.00" : String.format("%,.2f", v);
    }

    private TransactionInsight ins(Statement s, String type, String label, String value, String meta) {
        return TransactionInsight.builder()
                .statement(s).type(type).label(label).value(value).meta(meta).build();
    }

    // ── Column extraction helpers ─────────────────────────────────────────────

    private String getCol(Map<String, String> row, String... keys) {
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

    // ── Date parsing ─────────────────────────────────────────────────────────

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
            String c = raw.replaceAll("[₹,\\s]", "").trim();
            return c.isEmpty() ? null : new BigDecimal(c);
        } catch (NumberFormatException e) { return null; }
    }
}