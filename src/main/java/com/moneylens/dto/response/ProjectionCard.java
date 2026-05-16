package com.moneylens.dto.response;

public record ProjectionCard(

        // "You're burning ₹14,400/year on impulse food"
        String headline,

        // "That's a phone upgrade every year."
        String impact,

        // "12 months"
        String timeframe,

        // "leak" | "opportunity" | "compounding" | "risk"
        String type
) {}