package com.aman.orderflow.payment;

import com.aman.orderflow.common.exception.InvalidOrderStateException;
import com.aman.orderflow.common.exception.ResourceNotFoundException;
import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.messaging.outbox.OutboxEventWriter;
import com.aman.orderflow.order.domain.Order;
import com.aman.orderflow.order.domain.OrderItem;
import com.aman.orderflow.order.domain.OrderStatus;
import com.aman.orderflow.order.repository.OrderRepository;
import com.aman.orderflow.payment.domain.Payment;
import com.aman.orderflow.payment.domain.PaymentStatus;
import com.aman.orderflow.payment.dto.PaymentRequest;
import com.aman.orderflow.payment.repository.PaymentRepository;
import com.aman.orderflow.payment.service.PaymentService;
import com.aman.orderflow.security.UserPrincipal;
import com.aman.orderflow.user.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    private PaymentService paymentService;
    private final UserPrincipal owner = new UserPrincipal(7L, "buyer@example.com", "hash", Role.CUSTOMER);
    private final UserPrincipal stranger = new UserPrincipal(99L, "stranger@example.com", "hash", Role.CUSTOMER);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        paymentService = new PaymentService(paymentRepository, orderRepository, inventoryService, outboxEventWriter);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Order createdOrder() {
        Order order = new Order(7L, "buyer@example.com",
                List.of(new OrderItem(1L, 2, new BigDecimal("10.00"))));
        setId(order, 5L);
        return order;
    }

    @Test
    void pay_succeeds_confirmsOrderAndPublishesPaymentSucceeded() {
        Order order = createdOrder();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        Payment payment = paymentService.pay(new PaymentRequest(5L, false), "idem-key-1", owner);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getAmount()).isEqualByComparingTo("20.00");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(outboxEventWriter).enqueue(eq("PAYMENT"), any(), eq("PaymentSucceeded"), eq(1), any());
        verify(inventoryService, never()).release(any(), anyInt());
    }

    @Test
    void pay_simulatedFailure_releasesStockAndCancelsOrderAndPublishesPaymentFailed() {
        Order order = createdOrder();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        Payment payment = paymentService.pay(new PaymentRequest(5L, true), "idem-key-2", owner);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryService).release(1L, 2);
        verify(outboxEventWriter).enqueue(eq("PAYMENT"), any(), eq("PaymentFailed"), eq(1), any());
        verify(outboxEventWriter).enqueue(eq("ORDER"), eq(5L), eq("OrderCancelled"), eq(1), any());
    }

    @Test
    void pay_rejectsAttemptByAnotherCustomer() {
        Order order = createdOrder();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(5L, false), "idem-key-3", stranger))
                .isInstanceOf(AccessDeniedException.class);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void pay_rejectsOrderNotInCreatedState() {
        Order order = createdOrder();
        order.confirm();
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(5L, false), "idem-key-4", owner))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void pay_throwsNotFound_forNonexistentOrder() {
        when(orderRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(404L, false), "idem-key-5", owner))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
