package com.aman.orderflow.security;

import com.aman.orderflow.user.domain.Role;
import com.aman.orderflow.user.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Adapter between our domain User and Spring Security's UserDetails contract.
 * Kept separate from the User entity so the persistence model doesn't have to
 * implement a framework interface.
 */
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;

    public UserPrincipal(Long id, String email, String passwordHash, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole());
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
