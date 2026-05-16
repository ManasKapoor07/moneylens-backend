package com.moneylens.util;

import java.util.List;
import java.util.regex.Pattern;


public final class MerchantResolver {

    private MerchantResolver() {}

    // Known merchants — checked first (order matters: more specific first)
    private static final List<MerchantRule> KNOWN = List.of(
            new MerchantRule("(?i)zomato",   "Zomato"),
            new MerchantRule("(?i)blinkit",  "Blinkit"),
            new MerchantRule("(?i)amazon",   "Amazon"),
            new MerchantRule("(?i)swiggy",   "Swiggy"),
            new MerchantRule("(?i)airtel",   "Airtel"),
            new MerchantRule("(?i)uber",     "Uber"),
            new MerchantRule("(?i)rapido",   "Rapido"),
            new MerchantRule("(?i)roppen",   "Rapido"),
            new MerchantRule("(?i)google",   "Google"),
            new MerchantRule("(?i)netflix",  "Netflix"),
            new MerchantRule("(?i)spotify",  "Spotify"),
            new MerchantRule("(?i)hotstar",  "Hotstar"),
            new MerchantRule("(?i)phonepe",  "PhonePe"),
            new MerchantRule("(?i)paytm",    "Paytm")
    );

    // Patterns to strip from raw descriptions before extracting a name
    private static final Pattern STRIP = Pattern.compile(
            "/UPI/|/Blinki/|/Collec/|/UPIInt/|/Payvia/|/Pay [Tt]o/|/Paymen/|/Verifi/|"
                    + "@\\S+|[A-Z]{2,5} BANK[^/]*|\\d{8,}|[^\\w\\s]",
            Pattern.CASE_INSENSITIVE
    );

    public static String resolve(String description) {
        if (description == null || description.isBlank()) return "Unknown";

        // 1. Check known merchants
        for (MerchantRule rule : KNOWN) {
            if (rule.pattern.matcher(description).find()) return rule.name;
        }

        // 2. Strip noise and take first 1–2 meaningful words
        String cleaned = STRIP.matcher(description).replaceAll(" ").trim();
        String[] words = cleaned.split("\\s+");

        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (String word : words) {
            if (word.length() > 2 && added < 2) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase());
                added++;
            }
        }

        return sb.length() > 0 ? sb.toString() : description.substring(0, Math.min(20, description.length()));
    }

    private record MerchantRule(Pattern pattern, String name) {
        MerchantRule(String regex, String name) {
            this(Pattern.compile(regex), name);
        }
    }
}