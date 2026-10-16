package com.aman.orderflow.order.service;

import com.aman.orderflow.common.exception.ResourceNotFoundException;
import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.messaging.event.OrderCancelledPayload;
import com.aman.orderflow.messaging.event.OrderCreatedPayload;
import com.aman.orderflow.messaging.outbox.OutboxEventWriter;
import com.aman.orderflow.order.domain.Order;
import com.aman.orderflow.order.domain.OrderItem;
import com.aman.orderflow.order.dto.CreateOrderRequest;
import com.aman.orderflow.order.dto.OrderItemRequest;
import com.aman.orderflow.order.dto.OrderResponse;
import com.aman.orderflow.order.repository.OrderRepository;
import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.service.ProductService;
import com.aman.orderflow.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Every public method here returns OrderResponse (or a Page of it), not the Order
 * entity, and does the entity->DTO mapping itself while still inside its own
 * @Transactional method. Order.items is a lazy collection (open-in-view is
 * disabled - see application.yml) - if a controller received the raw Order and
 * mapped it to a DTO afterwards, that mapping would run after the transaction
 * (and the Hibernate session with it) had already closed, and touching
 * order.getItems() at that point throws LazyInitializationException. This was
 * an actual bug found via live testing in Stage 3 (cancel() masked it by
 * coincidentally touching getItems() itself while still inside its own
 * transaction) - see docs/learning-guide/03-order-transactions-state-machine.md.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final OutboxEventWriter outboxEventWriter;

    public OrderService(OrderRepository orderRepository, ProductService productService,
                         InventoryService inventoryService, OutboxEventWriter outboxEventWriter) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.outboxEventWriter = outboxEventWriter;
    }

    /**
     * Creates an order and reserves stock for every line item in a single transaction.
     * Prices are always read from the current Product record, never from the client
     * request, so a tampered or stale client-supplied price can never affect the total.
     * If reservation fails for any item, the whole method throws, Spring rolls back the
     * transaction, and no partial reservation or order row is left behind.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, UserPrincipal currentUser) {
        List<OrderItem> items = request.items().stream()
                .map(this::toOrderItem)
                .toList();

        for (OrderItemRequest itemRequest : request.items()) {
            inventoryService.reserve(itemRequest.productId(), itemRequest.quantity());
        }

        Order order = new Order(currentUser.getId(), currentUser.getUsername(), items);
        order = orderRepository.save(order);

        // Enqueued in the SAME transaction as the order row itself (transactional
        // outbox - see OutboxEventWriter/OutboxRelay): either both the order and
        // the event-to-be-published commit together, or neither does.
        outboxEventWriter.enqueue("ORDER", order.getId(), "OrderCreated", 1,
                new OrderCreatedPayload(order.getId(), order.getUserId(), order.getCustomerEmail(),
                        order.getTotalAmount()));

        return OrderResponse.from(order);
    }

    private OrderItem toOrderItem(OrderItemRequest itemRequest) {
        Product product = productService.getById(itemRequest.productId());
        if (!product.isActive()) {
            throw new ResourceNotFoundException("Product not available: " + product.getId());
        }
        return new OrderItem(product.getId(), itemRequest.quantity(), product.getPrice());
    }

    private Order loadEntity(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return OrderResponse.from(loadEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listByUser(Long userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable).map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listByCustomerEmail(String customerEmail, Pageable pageable) {
        return orderRepository.findByCustomerEmail(customerEmail, pageable).map(OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listAll(Pageable pageable) {
        return orderRepository.findAll(pageable).map(OrderResponse::from);
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        Order order = loadEntity(id);
        order.cancel();
        for (OrderItem item : order.getItems()) {
            inventoryService.release(item.getProductId(), item.getQuantity());
        }
        outboxEventWriter.enqueue("ORDER", order.getId(), "OrderCancelled", 1,
                new OrderCancelledPayload(order.getId(), order.getUserId()));
        return OrderResponse.from(order);
    }
}
