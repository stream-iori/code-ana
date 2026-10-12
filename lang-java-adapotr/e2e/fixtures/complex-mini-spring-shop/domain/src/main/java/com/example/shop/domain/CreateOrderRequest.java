package com.example.shop.domain;

import java.util.List;

public record CreateOrderRequest(String customerId, List<OrderItem> items, boolean expedited) {
}

