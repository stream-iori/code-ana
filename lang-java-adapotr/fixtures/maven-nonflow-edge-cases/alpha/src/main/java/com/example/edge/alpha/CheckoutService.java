package com.example.edge.alpha;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CheckoutService {
    private final Gateway gateway;

    public CheckoutService(@Qualifier("primaryGateway") Gateway gateway) {
        this.gateway = gateway;
    }
}

@Service
class MultipleConstructors {
    MultipleConstructors() {}
    MultipleConstructors(Gateway gateway) {}
}
