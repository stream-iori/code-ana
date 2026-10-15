package com.example.shop.service;

import com.example.shop.domain.dto.CreateOrderRequest;
import org.springframework.stereotype.Service;

@Service
public class OrderValidator {
    public void validate(CreateOrderRequest request) {
        if (request.getCustomerId() == null || request.getCustomerId().isEmpty()) {
            throw new IllegalArgumentException("customerId required");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("items required");
        }
    }
}
