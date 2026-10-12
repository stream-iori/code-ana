package com.example.shop.application;

import com.example.shop.domain.CreateOrderRequest;

public interface RiskPolicy {
    void check(CreateOrderRequest request);
}

