package com.example.shop.batch;

import com.example.shop.domain.Order;

public interface InventoryRestorer {
    void release(Order order);
}

