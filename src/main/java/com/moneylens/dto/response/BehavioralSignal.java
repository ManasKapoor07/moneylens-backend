package com.moneylens.dto.response;

public record BehavioralSignal(

        // "Post-Salary Splurge"
        String label,

        // "You spend 40% of your salary in 3 days of receiving it"
        String observation,

        // "impulsive" | "anxious" | "disciplined" | "avoidant" | "reactive"
        String emotion,

        // 1–10, where 10 = severe / deeply ingrained pattern
        int intensity
) {}