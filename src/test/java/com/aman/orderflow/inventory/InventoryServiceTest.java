package com.aman.orderflow.inventory;

import com.aman.orderflow.common.exception.InsufficientStockException;
import com.aman.orderflow.inventory.repository.InventoryRepository;
import com.aman.orderflow.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        inventoryService = new InventoryService(inventoryRepository);
    }

    @Test
    void reserve_throwsInsufficientStock_whenAtomicUpdateAffectsZeroRows() {
        when(inventoryRepository.reserve(1L, 5)).thenReturn(0);

        assertThatThrownBy(() -> inventoryService.reserve(1L, 5))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryRepository).reserve(1L, 5);
    }

    @Test
    void reserve_succeeds_whenAtomicUpdateAffectsOneRow() {
        when(inventoryRepository.reserve(anyLong(), anyInt())).thenReturn(1);

        inventoryService.reserve(1L, 5);

        verify(inventoryRepository).reserve(1L, 5);
    }

    @Test
    void release_throwsIllegalState_whenReservedQuantityWouldGoNegative() {
        when(inventoryRepository.release(1L, 3)).thenReturn(0);

        assertThatThrownBy(() -> inventoryService.release(1L, 3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adjustAvailable_throwsInsufficientStock_whenAdjustmentWouldGoNegative() {
        when(inventoryRepository.adjustAvailable(1L, -100)).thenReturn(0);

        assertThatThrownBy(() -> inventoryService.adjustAvailable(1L, -100))
                .isInstanceOf(InsufficientStockException.class);
    }
}
