package com.example.shop.domain.dto;

import com.example.shop.domain.entity.OrderItem;

import java.util.List;

public class CreateOrderRequest {
    private String customerId;
    private List<OrderItem> items;

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
}
