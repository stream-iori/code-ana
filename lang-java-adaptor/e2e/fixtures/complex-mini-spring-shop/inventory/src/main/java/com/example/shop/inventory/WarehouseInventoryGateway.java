package com.example.shop.inventory;

import com.example.shop.application.InventoryGateway;
import com.example.shop.application.OrderRejectedException;
import com.example.shop.domain.OrderItem;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class WarehouseInventoryGateway implements InventoryGateway {
    @Override
    public void reserve(List<OrderItem> items) {
        for (OrderItem item : items) {
            if (item.quantity() <= 0) {
                throw new OrderRejectedException("quantity must be positive: " + item.sku());
            }
        }
    }
}

