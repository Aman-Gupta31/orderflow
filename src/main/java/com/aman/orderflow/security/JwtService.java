package com.aman.orderflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates JWT access tokens. There is deliberately no refresh-token
 * mechanism in this project yet - see docs/learning-guide/05-security-jwt-auth.md
 * for why, and what that limits.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationSeconds;

    public JwtService(@Value("${app.jwt.secret}") String base64Secret,
                       @Value("${app.jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
        this.expirationSeconds = expirationSeconds;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public String generateToken(UserPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.getUsername())
                .claim("uid", principal.getId())
                .claim("role", principal.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the subject (email) if the token's signature and expiry are valid,
     * null otherwise. Callers still look the user up fresh via UserDetailsService
     * rather than trusting the token's embedded role claim, so a role change or
     * account deactivation takes effect on the very next request instead of only
     * after the token expires.
     */
    public String extractValidSubject(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
