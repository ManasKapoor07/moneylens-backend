package com.moneylens.dto.response;

public record MoneyPersonality(

        // "The Leaky Bucket" | "The Weekend Spender" |
        // "The Anxious Saver" | "The Ghost Saver" | "The Reactive Spender"
        String archetype,

        // 2 short human lines — no jargon
        // "You earn steadily but money slips out in ways you
        //  don't track. The problem isn't income — it's invisible exits."
        String description,

        // "impulsive" | "cautious" | "inconsistent" | "disciplined"
        String trait
) {}