package com.example.shop.notification;

import com.example.shop.domain.OrderCreatedEvent;

public class NotificationDispatcher {
    private final NotificationRegistry registry;

    public NotificationDispatcher(NotificationRegistry registry) {
        this.registry = registry;
    }

    public void dispatch(OrderCreatedEvent event) {
        registry.getOrderedChannels().forEach(channel -> channel.send(event));
    }
}

