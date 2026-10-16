package com.aman.orderflow.auth.service;

import com.aman.orderflow.auth.dto.AuthResponse;
import com.aman.orderflow.auth.dto.LoginRequest;
import com.aman.orderflow.auth.dto.RegisterRequest;
import com.aman.orderflow.common.exception.DuplicateResourceException;
import com.aman.orderflow.security.JwtService;
import com.aman.orderflow.security.UserPrincipal;
import com.aman.orderflow.user.domain.Role;
import com.aman.orderflow.user.domain.User;
import com.aman.orderflow.user.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Every self-registered account is a CUSTOMER - there is no "role" field in
     * RegisterRequest at all, so a client can never grant itself ADMIN. Admin
     * accounts are created only via the seeding mechanism (see AdminSeeder).
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered: " + request.email());
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), Role.CUSTOMER);
        userRepository.save(user);
        return issueToken(UserPrincipal.from(user));
    }

    public AuthResponse login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        return issueToken((UserPrincipal) authentication.getPrincipal());
    }

    private AuthResponse issueToken(UserPrincipal principal) {
        String token = jwtService.generateToken(principal);
        return AuthResponse.bearer(token, jwtService.getExpirationSeconds());
    }
}
