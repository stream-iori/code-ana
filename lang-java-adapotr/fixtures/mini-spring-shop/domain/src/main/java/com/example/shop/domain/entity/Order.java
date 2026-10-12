package com.example.shop.domain.entity;

import java.util.List;

public class Order {
    private String id;
    private String customerId;
    private List<OrderItem> items;
    private OrderStatus status;

    public Order() {}

    public Order(String customerId, List<OrderItem> items) {
        this.customerId = customerId;
        this.items = items;
        this.status = OrderStatus.NEW;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
}
