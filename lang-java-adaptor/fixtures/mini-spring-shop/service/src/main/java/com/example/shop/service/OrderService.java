package com.example.shop.service;

import com.example.shop.domain.dto.CreateOrderRequest;
import com.example.shop.domain.dto.OrderResult;
import com.example.shop.domain.entity.Order;
import com.example.shop.domain.entity.OrderItem;
import com.example.shop.domain.entity.OrderStatus;
import com.example.shop.domain.event.OrderCreatedEvent;
import com.example.shop.event.OrderEventPublisher;
import com.example.shop.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class OrderService extends BaseService {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderValidator validator;
    @Autowired
    private PriceCalculator calculator;
    @Autowired
    private OrderEventPublisher publisher;

    @Transactional
    public OrderResult createOrder(CreateOrderRequest request) {
        Objects.requireNonNull(request, "request");
        validator.validate(request);
        double total = calculator.calculate(request.getItems());
        double discount = applyDiscount(total);
        Order order = new Order(request.getCustomerId(), request.getItems());
        order.setStatus(OrderStatus.PENDING);
        Order saved = orderRepository.save(order);
        publisher.publish(new OrderCreatedEvent(saved));
        return new OrderResult(saved.getId(), total - discount);
    }

    public OrderResult createOrder(String customerId, List<OrderItem> items) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setCustomerId(customerId);
        req.setItems(items);
        return createOrder(req);
    }

    @Override
    protected double applyDiscount(double amount) {
        return amount > 100 ? amount * 0.1 : 0;
    }

    public long countExpensiveItems(List<OrderItem> items) {
        return items.stream()
                .filter(item -> item.getPrice() > 50)
                .count();
    }

    public Runnable cleanupTask() {
        return new Runnable() {
            @Override
            public void run() {
                orderRepository.deleteAll();
            }
        };
    }
}
