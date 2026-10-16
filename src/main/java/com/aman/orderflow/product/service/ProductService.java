package com.aman.orderflow.product.service;

import com.aman.orderflow.common.exception.DuplicateResourceException;
import com.aman.orderflow.common.exception.ResourceNotFoundException;
import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.dto.ProductRequest;
import com.aman.orderflow.product.dto.ProductUpdateRequest;
import com.aman.orderflow.product.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final InventoryService inventoryService;

    public ProductService(ProductRepository productRepository, InventoryService inventoryService) {
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
    }

    @Transactional
    public Product create(ProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new DuplicateResourceException("SKU already exists: " + request.sku());
        }
        Product product = new Product(request.name(), request.description(), request.sku(), request.price());
        product = productRepository.save(product);
        inventoryService.createInitialStock(product.getId(), request.initialQuantity());
        return product;
    }

    @Transactional
    @CacheEvict(value = "products", key = "#id")
    public Product update(Long id, ProductUpdateRequest request) {
        Product product = getById(id);
        product.update(request.name(), request.description(), request.price());
        return product;
    }

    @Transactional
    @CacheEvict(value = "products", key = "#id")
    public void deactivate(Long id) {
        Product product = getById(id);
        product.deactivate();
    }

    /**
     * The one cached read in this project (Module F). Not every GET is worth
     * caching: list/search results would need a cache key per distinct
     * (nameFilter, page, size, sort) combination, multiplying entries for a
     * query SQL/its index already serves efficiently, and invalidating "every
     * list variation that might contain this product" on a single update is
     * much messier than evicting one key by id. Get-by-id is the highest-value,
     * simplest case: single key per product, trivially evicted on write.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "#id")
    public Product getById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Product> list(String nameFilter, Pageable pageable) {
        if (StringUtils.hasText(nameFilter)) {
            return productRepository.findByActiveTrueAndNameContainingIgnoreCase(nameFilter, pageable);
        }
        return productRepository.findByActiveTrue(pageable);
    }
}
