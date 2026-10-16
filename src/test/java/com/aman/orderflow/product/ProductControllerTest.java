package com.aman.orderflow.product;

import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.dto.ProductRequest;
import com.aman.orderflow.product.service.ProductService;
import com.aman.orderflow.product.web.ProductController;
import com.aman.orderflow.security.AppUserDetailsService;
import com.aman.orderflow.security.JwtService;
import com.aman.orderflow.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Includes our real SecurityConfig (rather than relying on whatever Boot's test
 * slice does by default) so these tests prove the actual authorization rules -
 * GET is public, mutating endpoints need ADMIN - not just controller behavior
 * in isolation. JwtService/AppUserDetailsService are mocked because nothing in
 * these tests exercises real token parsing; @WithMockUser injects an
 * Authentication directly into the SecurityContext, bypassing the JWT filter
 * entirely (see docs/learning-guide/05-security-jwt-auth.md).
 */
@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    void getById_isPubliclyAccessible_withoutAnyToken() throws Exception {
        when(productService.getById(99L))
                .thenThrow(new com.aman.orderflow.common.exception.ResourceNotFoundException("Product not found: 99"));

        mockMvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void create_returns401_whenNoAuthenticationIsPresent() throws Exception {
        var request = new ProductRequest("Widget", "A widget", "SKU-1", new BigDecimal("9.99"), 10);

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void create_returns403_whenAuthenticatedWithoutAdminRole() throws Exception {
        var request = new ProductRequest("Widget", "A widget", "SKU-1", new BigDecimal("9.99"), 10);

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returns400_whenPriceIsMissing() throws Exception {
        String badRequestJson = """
                {"name": "Widget", "sku": "SKU-1", "initialQuantity": 10}
                """;

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(badRequestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("price"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returns201_whenAuthenticatedAsAdminWithValidRequest() throws Exception {
        Product saved = new Product("Widget", "A widget", "SKU-1", new BigDecimal("9.99"));
        setId(saved, 1L);
        when(productService.create(any())).thenReturn(saved);

        var request = new ProductRequest("Widget", "A widget", "SKU-1", new BigDecimal("9.99"), 10);

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.price").value(9.99));
    }

    private static void setId(Product product, Long id) {
        try {
            var field = Product.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(product, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
