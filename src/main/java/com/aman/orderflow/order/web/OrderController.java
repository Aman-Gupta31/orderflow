package com.aman.orderflow.order.web;

import com.aman.orderflow.order.dto.CreateOrderRequest;
import com.aman.orderflow.order.dto.OrderResponse;
import com.aman.orderflow.order.service.OrderService;
import com.aman.orderflow.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                 @AuthenticationPrincipal UserPrincipal currentUser) {
        var order = orderService.createOrder(request, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/me")
    public Page<OrderResponse> myOrders(@AuthenticationPrincipal UserPrincipal currentUser, Pageable pageable) {
        return orderService.listByUser(currentUser.getId(), pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @orderSecurity.canAccess(#id)")
    public OrderResponse getById(@PathVariable Long id) {
        return orderService.getById(id);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponse> listAll(@RequestParam(required = false) String customerEmail, Pageable pageable) {
        return customerEmail != null
                ? orderService.listByCustomerEmail(customerEmail, pageable)
                : orderService.listAll(pageable);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or @orderSecurity.canAccess(#id)")
    public OrderResponse cancel(@PathVariable Long id) {
        return orderService.cancel(id);
    }
}
