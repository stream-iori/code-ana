package com.example.shop.notification;

import com.example.shop.domain.OrderCreatedEvent;

public class AuditNotificationChannel implements NotificationChannel {
    @Override
    public void send(OrderCreatedEvent event) {
        event.order().getStatus();
    }
}

