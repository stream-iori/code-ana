package com.example.shop.batch;

public class PartialReconciliationException extends RuntimeException {
    public PartialReconciliationException(int failures) {
        super("reconciliation failures: " + failures);
    }
}

