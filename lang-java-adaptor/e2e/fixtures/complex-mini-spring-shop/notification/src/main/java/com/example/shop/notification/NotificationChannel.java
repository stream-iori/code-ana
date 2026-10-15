package com.example.shop.notification;

import com.example.shop.domain.OrderCreatedEvent;

public interface NotificationChannel {
    void send(OrderCreatedEvent event);
}

