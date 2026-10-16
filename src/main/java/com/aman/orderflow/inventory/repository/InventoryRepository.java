package com.aman.orderflow.inventory.repository;

import com.aman.orderflow.inventory.domain.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductId(Long productId);

    /**
     * Atomic conditional reservation: only succeeds (returns 1) if enough stock is
     * available at the moment the UPDATE runs. Safe under concurrent requests across
     * multiple application instances because the WHERE clause and the decrement happen
     * as a single statement evaluated by MySQL, not as a read-then-write from the app.
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
               SET i.quantityAvailable = i.quantityAvailable - :quantity,
                   i.quantityReserved = i.quantityReserved + :quantity
             WHERE i.productId = :productId
               AND i.quantityAvailable >= :quantity
            """)
    int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Modifying
    @Query("""
            UPDATE Inventory i
               SET i.quantityAvailable = i.quantityAvailable + :quantity,
                   i.quantityReserved = i.quantityReserved - :quantity
             WHERE i.productId = :productId
               AND i.quantityReserved >= :quantity
            """)
    int release(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * Admin stock adjustment (restock or correction). Only touches quantityAvailable;
     * the WHERE clause guards against the adjustment driving it negative.
     */
    @Modifying
    @Query("""
            UPDATE Inventory i
               SET i.quantityAvailable = i.quantityAvailable + :delta
             WHERE i.productId = :productId
               AND i.quantityAvailable + :delta >= 0
            """)
    int adjustAvailable(@Param("productId") Long productId, @Param("delta") int delta);
}
