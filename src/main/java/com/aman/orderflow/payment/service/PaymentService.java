package com.aman.orderflow.payment.service;

import com.aman.orderflow.common.exception.InvalidOrderStateException;
import com.aman.orderflow.common.exception.ResourceNotFoundException;
import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.messaging.event.OrderCancelledPayload;
import com.aman.orderflow.messaging.event.PaymentFailedPayload;
import com.aman.orderflow.messaging.event.PaymentSucceededPayload;
import com.aman.orderflow.messaging.outbox.OutboxEventWriter;
import com.aman.orderflow.order.domain.Order;
import com.aman.orderflow.order.domain.OrderItem;
import com.aman.orderflow.order.domain.OrderStatus;
import com.aman.orderflow.order.repository.OrderRepository;
import com.aman.orderflow.payment.domain.Payment;
import com.aman.orderflow.payment.domain.PaymentStatus;
import com.aman.orderflow.payment.dto.PaymentRequest;
import com.aman.orderflow.payment.repository.PaymentRepository;
import com.aman.orderflow.security.UserPrincipal;
import com.aman.orderflow.user.domain.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Mock payment provider: resolves synchronously (success or failure, decided
 * by PaymentRequest.simulateFailure - see its javadoc), never touches real
 * payment credentials, and never claims to be a real financial transaction.
 * The one-payment-per-idempotency-key database constraint plus
 * IdempotencyService (wired in PaymentController) are what make a retried
 * request safe to repeat instead of double-charging.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final OutboxEventWriter outboxEventWriter;

    public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository,
                           InventoryService inventoryService, OutboxEventWriter outboxEventWriter) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public Payment pay(PaymentRequest request, String idempotencyKey, UserPrincipal currentUser) {
        Order order = orderRepository.findById(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + request.orderId()));

        if (!order.getUserId().equals(currentUser.getId()) && currentUser.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("You do not have permission to pay for this order");
        }
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new InvalidOrderStateException(
                    "Order %d cannot be paid from status %s".formatted(order.getId(), order.getStatus()));
        }

        // Amount is always the order's own server-computed total - the client
        // never supplies or can influence it.
        Payment payment = new Payment(order.getId(), idempotencyKey,
                request.shouldSimulateFailure() ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED,
                order.getTotalAmount());
        payment = paymentRepository.save(payment);

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            order.confirm();
            outboxEventWriter.enqueue("PAYMENT", payment.getId(), "PaymentSucceeded", 1,
                    new PaymentSucceededPayload(payment.getId(), order.getId(), payment.getAmount()));
        } else {
            // A definite failure (not an uncertain/in-flight outcome) - safe to
            // release the reservation immediately. The order is cancelled
            // rather than left retryable in CREATED state, because retrying
            // would need to re-reserve stock from scratch anyway (it was just
            // released) - simpler and more honest to require a fresh order,
            // which naturally re-checks current stock at that moment.
            releaseReservedStock(order);
            order.cancel();
            outboxEventWriter.enqueue("PAYMENT", payment.getId(), "PaymentFailed", 1,
                    new PaymentFailedPayload(payment.getId(), order.getId(), payment.getAmount(),
                            "Simulated payment failure"));
            // The order really did transition to CANCELLED here too, not just
            // the payment - publish both events so a consumer that only cares
            // about order lifecycle (not payments) doesn't have to also
            // subscribe to payment events to learn its order got cancelled.
            outboxEventWriter.enqueue("ORDER", order.getId(), "OrderCancelled", 1,
                    new OrderCancelledPayload(order.getId(), order.getUserId()));
        }

        return payment;
    }

    private void releaseReservedStock(Order order) {
        List<OrderItem> items = order.getItems();
        for (OrderItem item : items) {
            inventoryService.release(item.getProductId(), item.getQuantity());
        }
    }

    @Transactional(readOnly = true)
    public List<Payment> getByOrderId(Long orderId, UserPrincipal currentUser) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!order.getUserId().equals(currentUser.getId()) && currentUser.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("You do not have permission to view payments for this order");
        }
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }
}
