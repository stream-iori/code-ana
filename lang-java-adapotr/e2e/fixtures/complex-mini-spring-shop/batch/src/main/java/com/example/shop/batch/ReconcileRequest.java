package com.example.shop.batch;

import com.example.shop.domain.Order;
import java.util.List;

public record ReconcileRequest(String sourceSystem, String operatorReason, List<Order> orders) {
}

