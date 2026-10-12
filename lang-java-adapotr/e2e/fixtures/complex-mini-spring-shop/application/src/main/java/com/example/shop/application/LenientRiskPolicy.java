package com.example.shop.application;

import com.example.shop.domain.CreateOrderRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component("lenientRiskPolicy")
public class LenientRiskPolicy implements RiskPolicy {
    @Override
    public void check(CreateOrderRequest request) {
        // Deliberately accepts every structurally valid order.
    }
}

