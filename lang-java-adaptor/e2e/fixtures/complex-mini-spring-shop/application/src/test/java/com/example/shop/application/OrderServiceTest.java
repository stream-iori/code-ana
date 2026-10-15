package com.example.shop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.shop.domain.CreateOrderRequest;
import com.example.shop.domain.Order;
import com.example.shop.domain.OrderItem;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderServiceTest {
    @Test
    void ordinaryOrderUsesAllThreeSideEffects() {
        AtomicInteger calls = new AtomicInteger();
        OrderService service = service(calls);

        service.placeOrder(new CreateOrderRequest(
                "alice", List.of(new OrderItem("SKU-1", 20, 1)), false));

        assertEquals(3, calls.get());
    }

    private static OrderService service(AtomicInteger calls) {
        OrderAdmissionPolicy admission = new OrderAdmissionPolicy();
        RiskPolicy risk = request -> { };
        InventoryGateway inventory = items -> calls.incrementAndGet();
        OrderRepository repository = order -> {
            calls.incrementAndGet();
            order.setId("ord-1");
            return order;
        };
        EventPublisher publisher = event -> calls.incrementAndGet();
        return new OrderService(admission, risk, inventory, repository, publisher);
    }
}
