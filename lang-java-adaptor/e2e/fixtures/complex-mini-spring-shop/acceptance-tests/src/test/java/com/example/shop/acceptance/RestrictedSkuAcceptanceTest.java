package com.example.shop.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.shop.application.EventPublisher;
import com.example.shop.application.InventoryGateway;
import com.example.shop.application.OrderAdmissionPolicy;
import com.example.shop.application.OrderRejectedException;
import com.example.shop.application.OrderRepository;
import com.example.shop.application.OrderService;
import com.example.shop.application.RiskPolicy;
import com.example.shop.domain.CreateOrderRequest;
import com.example.shop.domain.OrderItem;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RestrictedSkuAcceptanceTest {
    @Test
    void expeditedRestrictedSkuIsRejectedBeforeEveryExternalSideEffect() {
        AtomicInteger inventoryCalls = new AtomicInteger();
        AtomicInteger repositoryCalls = new AtomicInteger();
        AtomicInteger publisherCalls = new AtomicInteger();
        OrderService service = service(inventoryCalls, repositoryCalls, publisherCalls);

        assertThrows(OrderRejectedException.class, () -> service.placeOrder(
                new CreateOrderRequest("alice",
                        List.of(new OrderItem("X-RESTRICTED", 20, 1)), true)));

        assertEquals(0, inventoryCalls.get(), "inventory must not be reserved");
        assertEquals(0, repositoryCalls.get(), "order must not be saved");
        assertEquals(0, publisherCalls.get(), "event must not be published");
    }

    @Test
    void ordinaryRestrictedSkuRemainsAllowed() {
        AtomicInteger inventoryCalls = new AtomicInteger();
        AtomicInteger repositoryCalls = new AtomicInteger();
        AtomicInteger publisherCalls = new AtomicInteger();
        OrderService service = service(inventoryCalls, repositoryCalls, publisherCalls);

        service.placeOrder(new CreateOrderRequest("alice",
                List.of(new OrderItem("X-RESTRICTED", 20, 1)), false));

        assertEquals(1, inventoryCalls.get());
        assertEquals(1, repositoryCalls.get());
        assertEquals(1, publisherCalls.get());
    }

    private static OrderService service(
            AtomicInteger inventoryCalls,
            AtomicInteger repositoryCalls,
            AtomicInteger publisherCalls) {
        OrderAdmissionPolicy admission = new OrderAdmissionPolicy();
        RiskPolicy risk = request -> { };
        InventoryGateway inventory = items -> inventoryCalls.incrementAndGet();
        OrderRepository repository = order -> {
            repositoryCalls.incrementAndGet();
            order.setId("accepted-1");
            return order;
        };
        EventPublisher publisher = event -> publisherCalls.incrementAndGet();
        return new OrderService(admission, risk, inventory, repository, publisher);
    }
}
