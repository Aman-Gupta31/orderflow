package com.aman.orderflow.security;

import com.aman.orderflow.user.domain.Role;
import com.aman.orderflow.user.domain.User;
import com.aman.orderflow.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates exactly one ADMIN account on startup, but only when both ADMIN_EMAIL
 * and ADMIN_PASSWORD are supplied as environment variables - there is no
 * hardcoded fallback credential anywhere in this codebase. If either is unset,
 * seeding is skipped with a log warning rather than falling back to something
 * insecure. Controlled by app.security.seed-admin.enabled (default true); set
 * to false, or simply never set the two env vars, in any environment where this
 * convenience isn't wanted.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String adminEmail;
    private final String adminPassword;

    public AdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        @Value("${app.security.seed-admin.enabled:true}") boolean enabled,
                        @Value("${ADMIN_EMAIL:}") String adminEmail,
                        @Value("${ADMIN_PASSWORD:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(adminEmail) || !StringUtils.hasText(adminPassword)) {
            log.warn("Admin seeding skipped: set ADMIN_EMAIL and ADMIN_PASSWORD env vars to seed one.");
            return;
        }
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }
        userRepository.save(new User(adminEmail, passwordEncoder.encode(adminPassword), Role.ADMIN));
        log.info("Seeded initial ADMIN user: {}", adminEmail);
    }
}
