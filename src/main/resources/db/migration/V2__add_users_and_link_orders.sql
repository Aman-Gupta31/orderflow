CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email)
);

ALTER TABLE orders
    ADD COLUMN user_id BIGINT NOT NULL AFTER id,
    ADD CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id);
