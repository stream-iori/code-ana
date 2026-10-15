package com.example.shop.domain.dto;

public class OrderResult {
    private final String orderId;
    private final double finalPrice;

    public OrderResult(String orderId, double finalPrice) {
        this.orderId = orderId;
        this.finalPrice = finalPrice;
    }

    public String getOrderId() { return orderId; }
    public double getFinalPrice() { return finalPrice; }
}
