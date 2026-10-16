package com.aman.orderflow.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_at_purchase", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceAtPurchase;

    protected OrderItem() {
        // JPA
    }

    public OrderItem(Long productId, int quantity, BigDecimal unitPriceAtPurchase) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPriceAtPurchase = unitPriceAtPurchase;
    }

    void assignOrder(Order order) {
        this.order = order;
    }

    public BigDecimal lineTotal() {
        return unitPriceAtPurchase.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPriceAtPurchase() {
        return unitPriceAtPurchase;
    }
}
