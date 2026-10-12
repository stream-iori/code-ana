package com.example.shop.application;

import com.example.shop.domain.CreateOrderRequest;
import com.example.shop.domain.Order;
import com.example.shop.domain.OrderCreatedEvent;
import com.example.shop.domain.OrderItem;
import com.example.shop.domain.OrderResult;
import com.example.shop.domain.OrderStatus;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderAdmissionPolicy admissionPolicy;
    private final RiskPolicy riskPolicy;
    private final InventoryGateway inventoryGateway;
    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;

    public OrderService(
            OrderAdmissionPolicy admissionPolicy,
            @Qualifier("strictRiskPolicy") RiskPolicy riskPolicy,
            InventoryGateway inventoryGateway,
            @Qualifier("auditedOrderRepository") OrderRepository orderRepository,
            EventPublisher eventPublisher) {
        this.admissionPolicy = admissionPolicy;
        this.riskPolicy = riskPolicy;
        this.inventoryGateway = inventoryGateway;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderResult placeOrder(CreateOrderRequest request) {
        admissionPolicy.check(request);
        riskPolicy.check(request);
        inventoryGateway.reserve(request.items());
        Order order = new Order(request.customerId(), request.items());
        order.setStatus(OrderStatus.PENDING);
        Order saved = orderRepository.save(order);
        eventPublisher.publish(new OrderCreatedEvent(saved));
        return new OrderResult(saved.getId(), saved.getStatus());
    }

    public OrderResult placeOrder(String customerId, List<OrderItem> items) {
        return placeOrder(new CreateOrderRequest(customerId, items, false));
    }
}

