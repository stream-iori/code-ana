package com.example.shop.batch;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationReasonNormalizer {
    public String normalize(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("operator reason required");
        }
        return reason.trim().toUpperCase(Locale.ROOT);
    }
}

