package com.example.edge.beta;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EdgeController {
    @GetMapping("/v1/orders")
    public void handle() {}
}
