package com.example.shop;

import com.example.shop.domain.dto.CreateOrderRequest;
import com.example.shop.domain.dto.OrderResult;
import com.example.shop.domain.entity.OrderItem;
import com.example.shop.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Test
    void createsOrderWithDiscountAboveThreshold() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setCustomerId("alice");
        req.setItems(Arrays.asList(
                new OrderItem("sku-1", 80.0, 1),
                new OrderItem("sku-2", 50.0, 1)
        ));
        OrderResult result = orderService.createOrder(req);
        assertNotNull(result.getOrderId());
        assertEquals(117.0, result.getFinalPrice(), 0.01);
    }

    @Test
    void noDiscountBelowThreshold() {
        OrderResult result = orderService.createOrder("bob",
                Collections.singletonList(new OrderItem("sku-x", 50.0, 1)));
        assertEquals(50.0, result.getFinalPrice(), 0.01);
    }
}
