package com.example.shop.application;

public class OrderRejectedException extends RuntimeException {
    public OrderRejectedException(String message) {
        super(message);
    }
}

