package com.aman.orderflow.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "quantity_available", nullable = false)
    private int quantityAvailable;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    @Version
    private Long version;

    protected Inventory() {
        // JPA
    }

    public Inventory(Long productId, int quantityAvailable) {
        this.productId = productId;
        this.quantityAvailable = quantityAvailable;
        this.quantityReserved = 0;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantityAvailable() {
        return quantityAvailable;
    }

    public int getQuantityReserved() {
        return quantityReserved;
    }

    public Long getVersion() {
        return version;
    }
}
