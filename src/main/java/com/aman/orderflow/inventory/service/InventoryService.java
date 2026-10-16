package com.aman.orderflow.inventory.service;

import com.aman.orderflow.common.exception.InsufficientStockException;
import com.aman.orderflow.common.exception.ResourceNotFoundException;
import com.aman.orderflow.inventory.domain.Inventory;
import com.aman.orderflow.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public void createInitialStock(Long productId, int quantity) {
        inventoryRepository.save(new Inventory(productId, quantity));
    }

    @Transactional(readOnly = true)
    public Inventory getByProductId(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No inventory record for product " + productId));
    }

    /**
     * Reserves stock for a single product line. Throws if the atomic conditional
     * UPDATE affected zero rows, which means either the product has no inventory
     * row or there wasn't enough available stock at the moment of the attempt.
     * Caller is expected to run this inside a wider @Transactional boundary
     * (see OrderService#createOrder) so a failure here rolls back any reservations
     * already made for earlier items in the same order.
     */
    @Transactional
    public void reserve(Long productId, int quantity) {
        int updated = inventoryRepository.reserve(productId, quantity);
        if (updated == 0) {
            throw new InsufficientStockException(
                    "Insufficient stock for product " + productId + ", requested " + quantity);
        }
    }

    @Transactional
    public void release(Long productId, int quantity) {
        int updated = inventoryRepository.release(productId, quantity);
        if (updated == 0) {
            throw new IllegalStateException(
                    "Cannot release " + quantity + " units for product " + productId
                            + ": reserved quantity would go negative");
        }
    }

    @Transactional
    public void adjustAvailable(Long productId, int delta) {
        int updated = inventoryRepository.adjustAvailable(productId, delta);
        if (updated == 0) {
            throw new InsufficientStockException(
                    "Adjustment would make available stock negative for product " + productId);
        }
    }
}
