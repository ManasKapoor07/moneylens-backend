package com.moneylens.init;

import com.moneylens.entity.MerchantRule;
import com.moneylens.repository.MerchantRuleRepository;
import com.moneylens.service.MerchantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * MerchantRegistrySeeder
 *
 * Runs once at startup. If the merchant_rules table is empty, inserts the
 * full canonical seed set — a direct migration of KEYWORD_RULES from
 * TransactionMapper + MERCHANT_ALIAS from AIContextBuilderService.
 *
 * Safe to run on every startup — the emptiness check prevents duplicate inserts.
 */
@Component
public class MerchantRegistrySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MerchantRegistrySeeder.class);

    private final MerchantRuleRepository repository;
    private final MerchantRegistry       registry;

    public MerchantRegistrySeeder(MerchantRuleRepository repository, MerchantRegistry registry) {
        this.repository = repository;
        this.registry   = registry;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            log.info("MerchantRegistry already seeded ({} rules) — skipping", repository.count());
            return;
        }

        log.info("Seeding MerchantRegistry...");
        List<MerchantRule> rules = buildSeedRules();
        repository.saveAll(rules);
        registry.refreshCache();
        log.info("MerchantRegistry seeded — {} rules inserted", rules.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEED DATA
    // Direct migration of TransactionMapper.KEYWORD_RULES and
    // AIContextBuilderService.MERCHANT_ALIAS.
    //
    // Format: seed(pattern, normalizedName, category, subCategory, confidence)
    // ─────────────────────────────────────────────────────────────────────────

    private List<MerchantRule> buildSeedRules() {
        List<MerchantRule> r = new ArrayList<>();

        // ── EMI / Loan ────────────────────────────────────────────────────────
        r.add(s("bajaj fin",      "Bajaj Finserv",      "EMI / Loan",     "Personal Loan",    0.97));
        r.add(s("hdfc loan",      "HDFC Loan",          "EMI / Loan",     "Personal Loan",    0.97));
        r.add(s("icici loan",     "ICICI Loan",         "EMI / Loan",     "Personal Loan",    0.97));
        r.add(s("loan repay",     "Loan Repayment",     "EMI / Loan",     null,               0.95));
        r.add(s("equitas",        "Equitas Bank",       "EMI / Loan",     null,               0.90));
        r.add(s("nach debit",     "NACH Debit",         "EMI / Loan",     "Auto Debit",       0.95));
        r.add(s("ecs debit",      "ECS Debit",          "EMI / Loan",     "Auto Debit",       0.95));
        r.add(s("mandate debit",  "Mandate Debit",      "EMI / Loan",     "Auto Debit",       0.93));
        r.add(s(" emi ",          "EMI Payment",        "EMI / Loan",     null,               0.85));

        // ── Food & Dining ─────────────────────────────────────────────────────
        r.add(s("zomato",         "Zomato",             "Food & Dining",  "Food Delivery",    0.99));
        r.add(s("swiggy",         "Swiggy",             "Food & Dining",  "Food Delivery",    0.99));
        r.add(s("eatsure",        "EatSure",            "Food & Dining",  "Food Delivery",    0.98));
        r.add(s("faasos",         "Faasos",             "Food & Dining",  "Food Delivery",    0.98));
        r.add(s("box8",           "Box8",               "Food & Dining",  "Food Delivery",    0.98));
        r.add(s("behrouz",        "Behrouz Biryani",    "Food & Dining",  "Food Delivery",    0.97));
        r.add(s("freshmenu",      "FreshMenu",          "Food & Dining",  "Food Delivery",    0.97));
        r.add(s("mcdonald",       "McDonald's",         "Food & Dining",  "QSR",              0.99));
        r.add(s("dominos",        "Domino's",           "Food & Dining",  "QSR",              0.99));
        r.add(s("dominospizza",   "Domino's",           "Food & Dining",  "QSR",              0.99));
        r.add(s("pizza hut",      "Pizza Hut",          "Food & Dining",  "QSR",              0.99));
        r.add(s("pizzahut",       "Pizza Hut",          "Food & Dining",  "QSR",              0.99));
        r.add(s("kfc",            "KFC",                "Food & Dining",  "QSR",              0.99));
        r.add(s("subway",         "Subway",             "Food & Dining",  "QSR",              0.98));
        r.add(s("starbucks",      "Starbucks",          "Food & Dining",  "Café",             0.99));
        r.add(s("chaayos",        "Chaayos",            "Food & Dining",  "Café",             0.98));
        r.add(s("chai point",     "Chai Point",         "Food & Dining",  "Café",             0.98));
        r.add(s("burger king",    "Burger King",        "Food & Dining",  "QSR",              0.99));
        r.add(s("barbeque",       "Barbeque Nation",    "Food & Dining",  "Restaurant",       0.95));
        r.add(s("restaurant",     "Restaurant",         "Food & Dining",  "Restaurant",       0.80));
        r.add(s("bistro",         "Bistro",             "Food & Dining",  "Café",             0.85));
        r.add(s("dhaba",          "Dhaba",              "Food & Dining",  "Restaurant",       0.82));
        r.add(s("bakery",         "Bakery",             "Food & Dining",  "Bakery",           0.80));
        r.add(s("canteen",        "Canteen",            "Food & Dining",  "Canteen",          0.80));
        r.add(s("cafe",           "Café",               "Food & Dining",  "Café",             0.78));
        r.add(s("coffee",         "Coffee Shop",        "Food & Dining",  "Café",             0.75));
        r.add(s("tea",            "Tea Shop",           "Food & Dining",  "Café",             0.70));

        // ── Groceries ─────────────────────────────────────────────────────────
        r.add(s("blinkit",        "Blinkit",            "Groceries",      "Quick Commerce",   0.99));
        r.add(s("blinki",         "Blinkit",            "Groceries",      "Quick Commerce",   0.97));
        r.add(s("zepto",          "Zepto",              "Groceries",      "Quick Commerce",   0.99));
        r.add(s("bigbasket",      "BigBasket",          "Groceries",      "Online Grocery",   0.99));
        r.add(s("big basket",     "BigBasket",          "Groceries",      "Online Grocery",   0.99));
        r.add(s("grofers",        "Blinkit (Grofers)",  "Groceries",      "Online Grocery",   0.97));
        r.add(s("dmart",          "D-Mart",             "Groceries",      "Supermarket",      0.98));
        r.add(s("d-mart",         "D-Mart",             "Groceries",      "Supermarket",      0.98));
        r.add(s("reliance fresh", "Reliance Fresh",     "Groceries",      "Supermarket",      0.98));
        r.add(s("reliance smart", "Reliance Smart",     "Groceries",      "Supermarket",      0.97));
        r.add(s("more retail",    "More Retail",        "Groceries",      "Supermarket",      0.96));
        r.add(s("spencer",        "Spencer's",          "Groceries",      "Supermarket",      0.95));
        r.add(s("milkbasket",     "Milkbasket",         "Groceries",      "Online Grocery",   0.97));
        r.add(s("dunzo",          "Dunzo",              "Groceries",      "Quick Commerce",   0.96));
        r.add(s("instamart",      "Swiggy Instamart",   "Groceries",      "Quick Commerce",   0.97));
        r.add(s("jiomart",        "JioMart",            "Groceries",      "Online Grocery",   0.97));
        r.add(s("supermart",      "Supermart",          "Groceries",      "Online Grocery",   0.85));
        r.add(s("daily basket",   "Daily Basket",       "Groceries",      "Online Grocery",   0.88));
        r.add(s("grocery",        "Grocery",            "Groceries",      null,               0.75));

        // ── Shopping ──────────────────────────────────────────────────────────
        r.add(s("amazon",         "Amazon",             "Shopping",       "E-Commerce",       0.98));
        r.add(s("flipkart",       "Flipkart",           "Shopping",       "E-Commerce",       0.99));
        r.add(s("myntra",         "Myntra",             "Shopping",       "Fashion",          0.99));
        r.add(s("nykaa",          "Nykaa",              "Shopping",       "Beauty",           0.99));
        r.add(s("meesho",         "Meesho",             "Shopping",       "E-Commerce",       0.98));
        r.add(s("ajio",           "Ajio",               "Shopping",       "Fashion",          0.98));
        r.add(s("snapdeal",       "Snapdeal",           "Shopping",       "E-Commerce",       0.98));
        r.add(s("tatacliq",       "Tata CLiQ",          "Shopping",       "E-Commerce",       0.97));
        r.add(s("shopsy",         "Shopsy",             "Shopping",       "E-Commerce",       0.97));
        r.add(s("lenskart",       "Lenskart",           "Shopping",       "Eyewear",          0.98));
        r.add(s("pepperfry",      "Pepperfry",          "Shopping",       "Home & Furniture", 0.97));
        r.add(s("urban ladder",   "Urban Ladder",       "Shopping",       "Home & Furniture", 0.97));
        r.add(s("ikea",           "IKEA",               "Shopping",       "Home & Furniture", 0.99));
        r.add(s("firstcry",       "FirstCry",           "Shopping",       "Kids",             0.98));
        r.add(s("westside",       "Westside",           "Shopping",       "Fashion",          0.97));
        r.add(s("max fashion",    "Max Fashion",        "Shopping",       "Fashion",          0.97));
        r.add(s("shoppers stop",  "Shoppers Stop",      "Shopping",       "Fashion",          0.97));
        r.add(s("croma",          "Croma",              "Shopping",       "Electronics",      0.98));
        r.add(s("vijay sales",    "Vijay Sales",        "Shopping",       "Electronics",      0.97));
        r.add(s("reliance digital","Reliance Digital",  "Shopping",       "Electronics",      0.97));
        r.add(s("miniso",         "Miniso",             "Shopping",       "Lifestyle",        0.97));
        r.add(s("ekart",          "Flipkart/Ekart",     "Shopping",       "E-Commerce",       0.92));
        r.add(s("delhivery",      "Delhivery",          "Shopping",       "Delivery",         0.80));
        r.add(s("marketplace pri","Marketplace",        "Shopping",       "E-Commerce",       0.82));
        r.add(s("culinary brands","Amazon",             "Shopping",       "E-Commerce",       0.80));
        r.add(s("urbancompany",   "Urban Company",      "Shopping",       "Home Services",    0.95));
        r.add(s("urban company",  "Urban Company",      "Shopping",       "Home Services",    0.95));
        r.add(s("visage",         "Visage Lines",       "Shopping",       "Beauty",           0.88));

        // ── Transport ─────────────────────────────────────────────────────────
        r.add(s("uber",           "Uber",               "Transport",      "Cab",              0.99));
        r.add(s("ola ",           "Ola",                "Transport",      "Cab",              0.98));
        r.add(s("rapido",         "Rapido",             "Transport",      "Bike Taxi",        0.99));
        r.add(s("roppen",         "Rapido",             "Transport",      "Bike Taxi",        0.97));
        r.add(s("yulu",           "Yulu",               "Transport",      "EV Rental",        0.97));
        r.add(s("bounce",         "Bounce",             "Transport",      "Bike Rental",      0.90));
        r.add(s("irctc",          "IRCTC",              "Transport",      "Train",            0.99));
        r.add(s("indian rail",    "Indian Railways",    "Transport",      "Train",            0.98));
        r.add(s("railwire",       "Railwire",           "Transport",      "Train",            0.92));
        r.add(s("redbus",         "RedBus",             "Transport",      "Bus",              0.98));
        r.add(s("abhibus",        "AbhiBus",            "Transport",      "Bus",              0.97));
        r.add(s("indigo",         "IndiGo",             "Transport",      "Flight",           0.97));
        r.add(s("indigo airline", "IndiGo",             "Transport",      "Flight",           0.99));
        r.add(s("air india",      "Air India",          "Transport",      "Flight",           0.99));
        r.add(s("spicejet",       "SpiceJet",           "Transport",      "Flight",           0.99));
        r.add(s("akasa",          "Akasa Air",          "Transport",      "Flight",           0.99));
        r.add(s("vistara",        "Vistara",            "Transport",      "Flight",           0.99));
        r.add(s("fastag",         "FASTag",             "Transport",      "Toll",             0.98));
        r.add(s("filling station","Filling Station",    "Transport",      "Fuel",             0.80));
        r.add(s("parking",        "Parking",            "Transport",      "Parking",          0.88));

        // ── Fuel ──────────────────────────────────────────────────────────────
        r.add(s("petrol",         "Petrol Pump",        "Fuel",           null,               0.90));
        r.add(s("diesel",         "Diesel",             "Fuel",           null,               0.90));
        r.add(s("iocl",           "Indian Oil",         "Fuel",           null,               0.97));
        r.add(s("bpcl",           "Bharat Petroleum",   "Fuel",           null,               0.97));
        r.add(s("hpcl",           "Hindustan Petroleum","Fuel",           null,               0.97));
        r.add(s("hp pump",        "Hindustan Petroleum","Fuel",           null,               0.95));
        r.add(s("cng",            "CNG",                "Fuel",           null,               0.88));

        // ── Utilities ─────────────────────────────────────────────────────────
        r.add(s("airtel",         "Airtel",             "Utilities",      "Mobile / DTH",     0.95));
        r.add(s("jio",            "Jio",                "Utilities",      "Mobile",           0.95));
        r.add(s("bsnl",           "BSNL",               "Utilities",      "Mobile",           0.97));
        r.add(s("vodafone",       "Vodafone Vi",        "Utilities",      "Mobile",           0.97));
        r.add(s(" vi ",           "Vodafone Vi",        "Utilities",      "Mobile",           0.85));
        r.add(s("electricity",    "Electricity Bill",   "Utilities",      "Electricity",      0.90));
        r.add(s("bses",           "BSES",               "Utilities",      "Electricity",      0.97));
        r.add(s("bescom",         "BESCOM",             "Utilities",      "Electricity",      0.97));
        r.add(s("msedcl",         "MSEDCL",             "Utilities",      "Electricity",      0.97));
        r.add(s("tpddl",          "TPDDL",              "Utilities",      "Electricity",      0.97));
        r.add(s("tata power",     "Tata Power",         "Utilities",      "Electricity",      0.97));
        r.add(s("adani elec",     "Adani Electricity",  "Utilities",      "Electricity",      0.97));
        r.add(s("broadband",      "Broadband",          "Utilities",      "Internet",         0.88));
        r.add(s("act fibernet",   "ACT Fibernet",       "Utilities",      "Internet",         0.98));
        r.add(s("hathway",        "Hathway",            "Utilities",      "Internet",         0.97));
        r.add(s("excitel",        "Excitel",            "Utilities",      "Internet",         0.97));
        r.add(s("mahanagar gas",  "Mahanagar Gas",      "Utilities",      "Gas",              0.98));
        r.add(s("indraprastha",   "Indraprastha Gas",   "Utilities",      "Gas",              0.97));
        r.add(s("bbps",           "BBPS Bill Pay",      "Utilities",      null,               0.88));
        r.add(s("municipality",   "Municipality",       "Utilities",      "Civic",            0.85));
        r.add(s("water bill",     "Water Bill",         "Utilities",      "Water",            0.90));
        r.add(s("gas bill",       "Gas Bill",           "Utilities",      "Gas",              0.90));
        r.add(s("recharge",       "Mobile Recharge",    "Utilities",      "Mobile",           0.80));

        // ── Subscriptions ─────────────────────────────────────────────────────
        r.add(s("netflix",        "Netflix",            "Subscriptions",  "OTT",              0.99));
        r.add(s("hotstar",        "Disney+ Hotstar",    "Subscriptions",  "OTT",              0.99));
        r.add(s("disney",         "Disney+",            "Subscriptions",  "OTT",              0.97));
        r.add(s("prime video",    "Amazon Prime Video", "Subscriptions",  "OTT",              0.99));
        r.add(s("zee5",           "Zee5",               "Subscriptions",  "OTT",              0.99));
        r.add(s("sonyliv",        "SonyLIV",            "Subscriptions",  "OTT",              0.99));
        r.add(s("mxplayer",       "MX Player",          "Subscriptions",  "OTT",              0.97));
        r.add(s("jiocinema",      "JioCinema",          "Subscriptions",  "OTT",              0.98));
        r.add(s("spotify",        "Spotify",            "Subscriptions",  "Music",            0.99));
        r.add(s("gaana",          "Gaana",              "Subscriptions",  "Music",            0.98));
        r.add(s("jiosaavn",       "JioSaavn",           "Subscriptions",  "Music",            0.98));
        r.add(s("wynk",           "Wynk Music",         "Subscriptions",  "Music",            0.97));
        r.add(s("youtube premium","YouTube Premium",    "Subscriptions",  "OTT",              0.99));
        r.add(s("google one",     "Google One",         "Subscriptions",  "Cloud Storage",    0.99));
        r.add(s("icloud",         "iCloud",             "Subscriptions",  "Cloud Storage",    0.99));
        r.add(s("dropbox",        "Dropbox",            "Subscriptions",  "Cloud Storage",    0.98));
        r.add(s("canva",          "Canva",              "Subscriptions",  "Design Tool",      0.98));
        r.add(s("grammarly",      "Grammarly",          "Subscriptions",  "Productivity",     0.98));
        r.add(s("notion",         "Notion",             "Subscriptions",  "Productivity",     0.98));
        r.add(s("github",         "GitHub",             "Subscriptions",  "Dev Tools",        0.98));
        r.add(s("chatgpt",        "ChatGPT",            "Subscriptions",  "AI Tools",         0.99));
        r.add(s("openai",         "OpenAI",             "Subscriptions",  "AI Tools",         0.99));
        r.add(s("microsoft 365",  "Microsoft 365",      "Subscriptions",  "Productivity",     0.99));
        r.add(s("office 365",     "Microsoft 365",      "Subscriptions",  "Productivity",     0.99));
        r.add(s("adobe",          "Adobe",              "Subscriptions",  "Creative Tools",   0.97));
        r.add(s("figma",          "Figma",              "Subscriptions",  "Design Tool",      0.98));
        r.add(s("apple med",      "Apple",              "Subscriptions",  "Apple Services",   0.95));
        r.add(s("apple",          "Apple",              "Subscriptions",  "Apple Services",   0.90));

        // ── Entertainment ─────────────────────────────────────────────────────
        r.add(s("bookmyshow",     "BookMyShow",         "Entertainment",  "Tickets",          0.99));
        r.add(s("pvr",            "PVR Cinemas",        "Entertainment",  "Cinema",           0.98));
        r.add(s("inox",           "INOX",               "Entertainment",  "Cinema",           0.98));
        r.add(s("cinepolis",      "Cinépolis",          "Entertainment",  "Cinema",           0.98));
        r.add(s("steam",          "Steam",              "Entertainment",  "Gaming",           0.97));
        r.add(s("playstation",    "PlayStation",        "Entertainment",  "Gaming",           0.98));
        r.add(s("xbox",           "Xbox",               "Entertainment",  "Gaming",           0.98));
        r.add(s("gaming",         "Gaming",             "Entertainment",  "Gaming",           0.80));
        r.add(s("playgames",      "Play Games",         "Entertainment",  "Gaming",           0.85));

        // ── Healthcare ────────────────────────────────────────────────────────
        r.add(s("apollo",         "Apollo",             "Healthcare",     "Hospital/Pharmacy",0.97));
        r.add(s("practo",         "Practo",             "Healthcare",     "Teleconsultation",  0.98));
        r.add(s("1mg",            "1mg",                "Healthcare",     "Pharmacy",         0.99));
        r.add(s("pharmeasy",      "PharmEasy",          "Healthcare",     "Pharmacy",         0.99));
        r.add(s("netmeds",        "Netmeds",            "Healthcare",     "Pharmacy",         0.99));
        r.add(s("medplus",        "MedPlus",            "Healthcare",     "Pharmacy",         0.98));
        r.add(s("healthians",     "Healthians",         "Healthcare",     "Diagnostics",      0.97));
        r.add(s("thyrocare",      "Thyrocare",          "Healthcare",     "Diagnostics",      0.98));
        r.add(s("hospital",       "Hospital",           "Healthcare",     "Hospital",         0.90));
        r.add(s("pharmacy",       "Pharmacy",           "Healthcare",     "Pharmacy",         0.90));
        r.add(s("medical",        "Medical",            "Healthcare",     null,               0.80));
        r.add(s("diagnostic",     "Diagnostic Centre",  "Healthcare",     "Diagnostics",      0.88));
        r.add(s("ashok pharma",   "Ashok Pharma",       "Healthcare",     "Pharmacy",         0.95));
        r.add(s("pharma",         "Pharmacy",           "Healthcare",     "Pharmacy",         0.82));
        r.add(s("clinic",         "Clinic",             "Healthcare",     "Clinic",           0.85));
        r.add(s("cult.fit",       "Cult.fit",           "Healthcare",     "Fitness",          0.98));
        r.add(s("cure.fit",       "Cult.fit",           "Healthcare",     "Fitness",          0.98));
        r.add(s("curefit",        "Cult.fit",           "Healthcare",     "Fitness",          0.98));
        r.add(s("doctor",         "Doctor Visit",       "Healthcare",     "Consultation",     0.85));
        r.add(s("lab test",       "Lab Test",           "Healthcare",     "Diagnostics",      0.90));
        r.add(s("curelink",       "Curelink Health",    "Healthcare",     "Health Platform",  0.95));

        // ── Investment ────────────────────────────────────────────────────────
        r.add(s("zerodha",        "Zerodha",            "Investment",     "Stocks",           0.99));
        r.add(s("iccl",           "Zerodha/NSE",        "Investment",     "Stocks",           0.95));
        r.add(s("groww",          "Groww",              "Investment",     "Mutual Funds",     0.99));
        r.add(s("upstox",         "Upstox",             "Investment",     "Stocks",           0.99));
        r.add(s("angel",          "Angel One",          "Investment",     "Stocks",           0.95));
        r.add(s("mutual fund",    "Mutual Fund",        "Investment",     "Mutual Funds",     0.95));
        r.add(s("sbimf",          "SBI Mutual Fund",    "Investment",     "Mutual Funds",     0.98));
        r.add(s("hdfcmf",         "HDFC Mutual Fund",   "Investment",     "Mutual Funds",     0.98));
        r.add(s("nps",            "NPS",                "Investment",     "Pension",          0.92));
        r.add(s("ppf",            "PPF",                "Investment",     "Pension",          0.92));
        r.add(s("lic ",           "LIC",                "Investment",     "Insurance",        0.95));
        r.add(s("insurance",      "Insurance",          "Investment",     "Insurance",        0.85));
        r.add(s("icici pru",      "ICICI Prudential",   "Investment",     "Insurance",        0.97));
        r.add(s("hdfc life",      "HDFC Life",          "Investment",     "Insurance",        0.97));
        r.add(s("sip",            "SIP",                "Investment",     "Mutual Funds",     0.88));
        r.add(s("gold bond",      "Sovereign Gold Bond","Investment",     "Gold",             0.95));
        r.add(s("smallcase",      "Smallcase",          "Investment",     "Stocks",           0.97));
        r.add(s("coin by",        "Zerodha Coin",       "Investment",     "Mutual Funds",     0.96));

        // ── Education ─────────────────────────────────────────────────────────
        r.add(s("udemy",          "Udemy",              "Education",      "Online Course",    0.99));
        r.add(s("coursera",       "Coursera",           "Education",      "Online Course",    0.99));
        r.add(s("unacademy",      "Unacademy",          "Education",      "Online Course",    0.99));
        r.add(s("byju",           "BYJU'S",             "Education",      "EdTech",           0.99));
        r.add(s("vedantu",        "Vedantu",            "Education",      "EdTech",           0.99));
        r.add(s("upgrad",         "upGrad",             "Education",      "Online Course",    0.99));
        r.add(s("simplilearn",    "Simplilearn",        "Education",      "Online Course",    0.98));
        r.add(s("physicswallah",  "Physics Wallah",     "Education",      "EdTech",           0.98));
        r.add(s("school fee",     "School Fee",         "Education",      "School",           0.92));
        r.add(s("school/trf",     "School Fee",         "Education",      "School",           0.90));
        r.add(s("public school",  "School",             "Education",      "School",           0.88));
        r.add(s("tuition",        "Tuition",            "Education",      "Tutoring",         0.88));
        r.add(s("coaching",       "Coaching",           "Education",      "Coaching",         0.85));
        r.add(s("college fee",    "College Fee",        "Education",      "College",          0.92));
        r.add(s("university",     "University",         "Education",      "College",          0.87));

        // ── Rent ──────────────────────────────────────────────────────────────
        r.add(s("nobroker",       "NoBroker",           "Rent",           null,               0.97));
        r.add(s("nestaway",       "Nestaway",           "Rent",           null,               0.97));
        r.add(s("house rent",     "House Rent",         "Rent",           null,               0.92));
        r.add(s("flat rent",      "Flat Rent",          "Rent",           null,               0.92));
        r.add(s("pg rent",        "PG Rent",            "Rent",           "PG",               0.90));
        r.add(s("rental",         "Rental",             "Rent",           null,               0.80));
        r.add(s("magicbricks",    "MagicBricks",        "Rent",           null,               0.88));
        r.add(s("99acres",        "99acres",            "Rent",           null,               0.88));

        // ── ATM / Cash ────────────────────────────────────────────────────────
        r.add(s("cash withdraw",  "Cash Withdrawal",    "Cash Withdrawal",null,               0.97));
        r.add(s("atm ",           "ATM Withdrawal",     "Cash Withdrawal",null,               0.97));
        r.add(s("cdm",            "Cash Deposit Machine","Cash Withdrawal",null,              0.90));
        r.add(s("cwdr",           "Cash Withdrawal",    "Cash Withdrawal",null,               0.95));

        // ── Tax / Government ──────────────────────────────────────────────────
        r.add(s("income tax",     "Income Tax",         "Tax",            null,               0.97));
        r.add(s("gst",            "GST Payment",        "Tax",            null,               0.90));
        r.add(s("tds",            "TDS",                "Tax",            null,               0.93));
        r.add(s("epfo",           "EPFO / PF",          "Tax",            null,               0.95));
        r.add(s("pf ",            "Provident Fund",     "Tax",            null,               0.88));

        // ── Personal Care ─────────────────────────────────────────────────────
        r.add(s("salon",          "Salon",              "Personal Care",  null,               0.88));
        r.add(s("haircut",        "Haircut",            "Personal Care",  null,               0.90));
        r.add(s("spa",            "Spa",                "Personal Care",  "Spa",              0.85));
        r.add(s("beauty",         "Beauty",             "Personal Care",  null,               0.80));
        r.add(s("nails",          "Nail Studio",        "Personal Care",  null,               0.82));

        // ── Charitable ────────────────────────────────────────────────────────
        r.add(s("temple",         "Temple Donation",    "Charitable",     null,               0.82));
        r.add(s("donation",       "Donation",           "Charitable",     null,               0.85));
        r.add(s("charity",        "Charity",            "Charitable",     null,               0.87));
        r.add(s("ngo",            "NGO",                "Charitable",     null,               0.85));

        // ── Miscellaneous well-known services ─────────────────────────────────
        r.add(s("stazy",          "Stazy",              "Shopping",       "Lifestyle",        0.88));
        r.add(s("playall",        "PlayAll",            "Entertainment",  "Sports",           0.88));
        r.add(s("google",         "Google",             "Subscriptions",  "Google Services",  0.80));

        return r;
    }

    /** Shorthand for seed() to keep the list readable above. */
    private MerchantRule s(
            String pattern, String name, String category, String sub, double confidence
    ) {
        return MerchantRule.seed(pattern, name, category, sub, confidence);
    }
}