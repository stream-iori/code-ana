package com.example.shop.batch;

import com.example.shop.domain.Order;

public interface BatchAuditTrail {
    void record(Order order, String reason);
}

