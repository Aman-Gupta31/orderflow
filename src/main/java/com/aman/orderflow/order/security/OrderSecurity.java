package com.aman.orderflow.order.security;

import com.aman.orderflow.order.repository.OrderRepository;
import com.aman.orderflow.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Backs @PreAuthorize("hasRole('ADMIN') or @orderSecurity.canAccess(#id)") on
 * OrderController. Returns true (letting the request through to the controller)
 * when the order doesn't exist at all, deliberately - otherwise a nonexistent
 * order would surface as 403 Forbidden instead of the correct 404 Not Found,
 * which the controller method itself still throws via OrderService.getById.
 */
@Component("orderSecurity")
public class OrderSecurity {

    private final OrderRepository orderRepository;

    public OrderSecurity(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public boolean canAccess(Long orderId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return false;
        }
        return orderRepository.findById(orderId)
                .map(order -> order.getUserId().equals(principal.getId()))
                .orElse(true);
    }
}
