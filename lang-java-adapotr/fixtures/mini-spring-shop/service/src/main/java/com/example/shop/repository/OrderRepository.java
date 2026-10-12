package com.example.shop.repository;

import com.example.shop.domain.entity.Order;

import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(String id);
    void deleteAll();
}
