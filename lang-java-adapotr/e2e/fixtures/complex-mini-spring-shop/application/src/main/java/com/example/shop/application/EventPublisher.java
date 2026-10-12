package com.example.shop.application;

import com.example.shop.domain.OrderCreatedEvent;

public interface EventPublisher {
    void publish(OrderCreatedEvent event);
}

