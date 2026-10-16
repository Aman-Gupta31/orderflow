package com.aman.orderflow.product.web;

import com.aman.orderflow.product.dto.ProductRequest;
import com.aman.orderflow.product.dto.ProductResponse;
import com.aman.orderflow.product.dto.ProductUpdateRequest;
import com.aman.orderflow.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        var product = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(product));
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return ProductResponse.from(productService.getById(id));
    }

    @GetMapping
    public Page<ProductResponse> list(@RequestParam(required = false) String name, Pageable pageable) {
        return productService.list(name, pageable).map(ProductResponse::from);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return ProductResponse.from(productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        productService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
