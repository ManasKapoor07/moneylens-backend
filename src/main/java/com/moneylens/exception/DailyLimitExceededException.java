package com.moneylens.exception;

public class DailyLimitExceededException extends RuntimeException {
    private final int limit;
    private final int used;

    public DailyLimitExceededException(int limit, int used) {
        super("Daily AI limit reached (" + used + "/" + limit + " tokens used). Resets at midnight.");
        this.limit = limit;
        this.used  = used;
    }

    public int getLimit() { return limit; }
    public int getUsed()  { return used; }
}