package com.example.shop.persistence;

import com.example.shop.application.OrderRepository;
import com.example.shop.domain.Order;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

@Repository("inMemoryOrderRepository")
public class InMemoryOrderRepository implements OrderRepository {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Order> orders = new LinkedHashMap<>();

    @Override
    public Order save(Order order) {
        if (order.getId() == null) {
            order.setId("ord-" + sequence.incrementAndGet());
        }
        orders.put(order.getId(), order);
        return order;
    }
}

