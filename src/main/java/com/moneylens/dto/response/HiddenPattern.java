package com.moneylens.dto.response;

public record HiddenPattern(

        // "The Sunday Drain"
        String title,

        // "Sundays account for 28% of your weekly spend — and you never noticed."
        String insight,

        // "timing" | "merchant" | "category" | "behavioral"
        String category
) {}