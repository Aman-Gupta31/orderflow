package com.aman.orderflow.user.dto;

import com.aman.orderflow.user.domain.Role;
import com.aman.orderflow.user.domain.User;

import java.time.Instant;

public record UserResponse(Long id, String email, Role role, Instant createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
