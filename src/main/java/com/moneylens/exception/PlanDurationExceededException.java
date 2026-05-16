package com.moneylens.exception;

public class PlanDurationExceededException extends RuntimeException {
    public PlanDurationExceededException(String message) {
        super(message);
    }
}