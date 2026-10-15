package com.example.shop.event;

import com.example.shop.domain.event.OrderCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private final ApplicationEventPublisher delegate;

    public OrderEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    public void publish(OrderCreatedEvent event) {
        delegate.publishEvent(event);
    }
}
