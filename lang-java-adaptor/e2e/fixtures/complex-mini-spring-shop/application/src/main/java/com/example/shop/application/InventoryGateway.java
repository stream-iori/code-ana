package com.example.shop.application;

import com.example.shop.domain.OrderItem;
import java.util.List;

public interface InventoryGateway {
    void reserve(List<OrderItem> items);
}

