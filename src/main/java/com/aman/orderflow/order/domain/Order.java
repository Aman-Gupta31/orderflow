package com.aman.orderflow.order.domain;

import com.aman.orderflow.common.exception.InvalidOrderStateException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST, orphanRemoval = false)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
        // JPA
    }

    public Order(Long userId, String customerEmail, List<OrderItem> items) {
        this.userId = userId;
        this.customerEmail = customerEmail;
        this.status = OrderStatus.CREATED;
        this.items = new ArrayList<>(items);
        this.items.forEach(item -> item.assignOrder(this));
        this.totalAmount = this.items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        if (!status.canCancel()) {
            throw new InvalidOrderStateException(
                    "Order %d cannot be cancelled from status %s".formatted(id, status));
        }
        this.status = OrderStatus.CANCELLED;
    }

    /**
     * CREATED -> CONFIRMED, driven by a successful payment (PaymentService).
     * Only valid from CREATED: a second successful payment attempt against an
     * already-CONFIRMED order (which idempotency should prevent from even being
     * attempted) would otherwise silently re-confirm a no-op.
     */
    public void confirm() {
        if (status != OrderStatus.CREATED) {
            throw new InvalidOrderStateException(
                    "Order %d cannot be confirmed from status %s".formatted(id, status));
        }
        this.status = OrderStatus.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
