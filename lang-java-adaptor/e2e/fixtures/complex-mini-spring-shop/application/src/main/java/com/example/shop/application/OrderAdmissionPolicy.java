package com.example.shop.application;

import com.example.shop.domain.CreateOrderRequest;
import org.springframework.stereotype.Component;

@Component
public class OrderAdmissionPolicy {
    public void check(CreateOrderRequest request) {
        if (request.customerId() == null || request.customerId().isBlank()) {
            throw new OrderRejectedException("customerId required");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new OrderRejectedException("items required");
        }
    }
}

