package com.example.edge.alpha;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = {"/v1", "/v2"})
public class EdgeController {
    @RequestMapping(path = {"/orders", "/purchases"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public void handle() {}
}
