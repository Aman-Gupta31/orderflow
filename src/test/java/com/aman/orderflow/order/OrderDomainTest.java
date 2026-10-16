package com.aman.orderflow.order;

import com.aman.orderflow.common.exception.InvalidOrderStateException;
import com.aman.orderflow.order.domain.Order;
import com.aman.orderflow.order.domain.OrderItem;
import com.aman.orderflow.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDomainTest {

    @Test
    void totalAmount_isComputedServerSideFromLineItems_notFromClientInput() {
        List<OrderItem> items = List.of(
                new OrderItem(1L, 2, new BigDecimal("10.00")),
                new OrderItem(2L, 1, new BigDecimal("5.50"))
        );

        Order order = new Order(1L, "buyer@example.com", items);

        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.50");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void cancel_transitionsCreatedOrderToCancelled() {
        Order order = new Order(1L, "buyer@example.com",
                List.of(new OrderItem(1L, 1, new BigDecimal("10.00"))));

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancel_rejectsAlreadyCancelledOrder() {
        Order order = new Order(1L, "buyer@example.com",
                List.of(new OrderItem(1L, 1, new BigDecimal("10.00"))));
        order.cancel();

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderStateException.class);
    }
}
