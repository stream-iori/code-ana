package com.example.shop.application;

import com.example.shop.domain.CreateOrderRequest;
import org.springframework.stereotype.Component;

@Component("strictRiskPolicy")
public class StrictRiskPolicy implements RiskPolicy {
    @Override
    public void check(CreateOrderRequest request) {
        double total = request.items().stream()
                .mapToDouble(item -> item.price() * item.quantity())
                .sum();
        if (total > 10_000) {
            throw new OrderRejectedException("risk limit exceeded");
        }
    }
}

