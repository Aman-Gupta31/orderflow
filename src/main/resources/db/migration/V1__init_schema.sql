CREATE TABLE products (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(255)   NOT NULL,
    description     VARCHAR(2000),
    sku             VARCHAR(64)    NOT NULL,
    price           DECIMAL(12, 2) NOT NULL,
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_products_sku UNIQUE (sku)
);

CREATE TABLE inventory (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id           BIGINT  NOT NULL,
    quantity_available   INT     NOT NULL DEFAULT 0,
    quantity_reserved    INT     NOT NULL DEFAULT 0,
    version              BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_product UNIQUE (product_id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_inventory_available_nonneg CHECK (quantity_available >= 0),
    CONSTRAINT chk_inventory_reserved_nonneg CHECK (quantity_reserved >= 0)
);

CREATE TABLE orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_email  VARCHAR(255)   NOT NULL,
    status          VARCHAR(32)    NOT NULL,
    total_amount    DECIMAL(12, 2) NOT NULL,
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_customer_email ON orders (customer_email);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id                  BIGINT         NOT NULL,
    product_id                BIGINT         NOT NULL,
    quantity                  INT            NOT NULL,
    unit_price_at_purchase    DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_order_items_quantity_positive CHECK (quantity > 0)
);
