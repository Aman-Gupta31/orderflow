package com.aman.orderflow.order;

import com.aman.orderflow.common.exception.InsufficientStockException;
import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.messaging.outbox.OutboxEventWriter;
import com.aman.orderflow.order.domain.Order;
import com.aman.orderflow.order.domain.OrderItem;
import com.aman.orderflow.order.dto.CreateOrderRequest;
import com.aman.orderflow.order.dto.OrderItemRequest;
import com.aman.orderflow.order.dto.OrderResponse;
import com.aman.orderflow.order.repository.OrderRepository;
import com.aman.orderflow.order.service.OrderService;
import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.service.ProductService;
import com.aman.orderflow.security.UserPrincipal;
import com.aman.orderflow.user.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductService productService;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    private OrderService orderService;
    private final UserPrincipal currentUser = new UserPrincipal(7L, "buyer@example.com", "hash", Role.CUSTOMER);

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orderService = new OrderService(orderRepository, productService, inventoryService, outboxEventWriter);
    }

    @Test
    void createOrder_usesServerSideProductPrice_ignoringAnyClientSuppliedPrice() {
        Product widget = new Product("Widget", "desc", "SKU-1", new BigDecimal("19.99"));
        setId(widget, 1L);
        when(productService.getById(1L)).thenReturn(widget);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateOrderRequest(List.of(new OrderItemRequest(1L, 3)));

        OrderResponse response = orderService.createOrder(request, currentUser);

        assertThat(response.totalAmount()).isEqualByComparingTo("59.97");
        assertThat(response.userId()).isEqualTo(7L);
        verify(inventoryService).reserve(1L, 3);
        verify(outboxEventWriter).enqueue(eq("ORDER"), any(), eq("OrderCreated"), eq(1), any());
    }

    @Test
    void createOrder_propagatesInsufficientStock_andNeverSavesTheOrder() {
        Product widget = new Product("Widget", "desc", "SKU-1", new BigDecimal("19.99"));
        setId(widget, 1L);
        when(productService.getById(1L)).thenReturn(widget);
        doThrow(new InsufficientStockException("no stock")).when(inventoryService).reserve(1L, 100);

        var request = new CreateOrderRequest(List.of(new OrderItemRequest(1L, 100)));

        assertThatThrownBy(() -> orderService.createOrder(request, currentUser))
                .isInstanceOf(InsufficientStockException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancel_releasesReservedStockForEveryLineItem() {
        Order existing = new Order(7L, "buyer@example.com",
                List.of(new OrderItem(1L, 2, new BigDecimal("10.00"))));
        setId(existing, 42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(existing));

        OrderResponse response = orderService.cancel(42L);

        assertThat(response.status()).isEqualTo(com.aman.orderflow.order.domain.OrderStatus.CANCELLED);
        verify(inventoryService).release(1L, 2);
        verify(outboxEventWriter).enqueue(eq("ORDER"), eq(42L), eq("OrderCancelled"), eq(1), any());
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
