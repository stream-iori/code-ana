package com.example.shop.notification;

import com.example.shop.application.EventPublisher;
import com.example.shop.domain.OrderCreatedEvent;

public class OrderEventPublisher implements EventPublisher {
    private final NotificationDispatcher dispatcher;

    public OrderEventPublisher(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(OrderCreatedEvent event) {
        dispatcher.dispatch(event);
    }
}

