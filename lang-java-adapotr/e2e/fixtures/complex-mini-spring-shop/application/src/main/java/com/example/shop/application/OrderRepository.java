package com.example.shop.application;

import com.example.shop.domain.Order;

public interface OrderRepository {
    Order save(Order order);
}

