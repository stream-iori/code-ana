package com.example.shop.notification;

import com.example.shop.domain.OrderCreatedEvent;

public class SmsNotificationChannel extends AbstractNotificationChannel {
    @Override
    public void send(OrderCreatedEvent event) {
        orderId(event);
    }
}

