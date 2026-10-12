package com.example.shop.service;

import com.example.shop.domain.entity.OrderItem;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PriceCalculator {
    public double calculate(List<OrderItem> items) {
        return items.stream().mapToDouble(OrderItem::getPrice).sum();
    }
}
