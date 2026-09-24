package com.nexus.supplychain.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {

    @GetMapping(value = {
        "/",
        "/login",
        "/dashboard",
        "/catalog",
        "/orders",
        "/audit-logs",
        "/users",
        "/reference-data",
        "/monitoring"
    })
    public String index() {
        return "forward:/index.html";
    }
}
