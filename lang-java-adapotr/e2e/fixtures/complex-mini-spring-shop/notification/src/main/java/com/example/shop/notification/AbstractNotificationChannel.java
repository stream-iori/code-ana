package com.example.shop.notification;

import com.example.shop.domain.OrderCreatedEvent;

public abstract class AbstractNotificationChannel implements NotificationChannel {
    protected String orderId(OrderCreatedEvent event) {
        return event.order().getId();
    }
}

