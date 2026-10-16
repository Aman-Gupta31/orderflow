package com.aman.orderflow.inventory.web;

import com.aman.orderflow.inventory.domain.Inventory;
import com.aman.orderflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{productId}")
    public InventoryResponse getByProductId(@PathVariable Long productId) {
        return InventoryResponse.from(inventoryService.getByProductId(productId));
    }

    @PatchMapping("/{productId}/adjust")
    public InventoryResponse adjust(@PathVariable Long productId, @Valid @RequestBody AdjustRequest request) {
        inventoryService.adjustAvailable(productId, request.delta());
        return InventoryResponse.from(inventoryService.getByProductId(productId));
    }

    public record AdjustRequest(@NotNull Integer delta) {
    }

    public record InventoryResponse(Long productId, int quantityAvailable, int quantityReserved) {
        public static InventoryResponse from(Inventory inventory) {
            return new InventoryResponse(inventory.getProductId(), inventory.getQuantityAvailable(),
                    inventory.getQuantityReserved());
        }
    }
}
