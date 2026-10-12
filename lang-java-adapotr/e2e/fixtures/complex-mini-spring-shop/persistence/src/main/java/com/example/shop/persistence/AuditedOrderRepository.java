package com.example.shop.persistence;

import com.example.shop.application.OrderRepository;
import com.example.shop.domain.Order;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

@Repository("auditedOrderRepository")
public class AuditedOrderRepository implements OrderRepository {
    private final OrderRepository delegate;

    public AuditedOrderRepository(
            @Qualifier("inMemoryOrderRepository") OrderRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Order save(Order order) {
        Order saved = delegate.save(order);
        recordAudit(saved);
        return saved;
    }

    private void recordAudit(Order order) {
        // Deterministic fixture: the indexed call and field reads are the evidence.
        order.getId();
        order.getStatus();
    }
}

