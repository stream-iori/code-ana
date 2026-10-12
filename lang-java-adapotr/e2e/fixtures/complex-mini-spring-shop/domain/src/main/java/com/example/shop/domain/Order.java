package com.example.shop.domain;

import java.util.List;

public class Order {
    private String id;
    private final String customerId;
    private final List<OrderItem> items;
    private OrderStatus status;

    public Order(String customerId, List<OrderItem> items) {
        this.customerId = customerId;
        this.items = List.copyOf(items);
        this.status = OrderStatus.NEW;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public List<OrderItem> getItems() { return items; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
}

