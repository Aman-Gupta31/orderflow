package com.aman.orderflow.security;

import com.aman.orderflow.user.domain.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    // Test-only fixture - never used outside this test JVM.
    private static final String SECRET =
            "Hx3XtlETJ6iYRnNq5uIsNDp6DzxGDPY+ulCUmb+2W5qHNiMqSye+j7Y5IQZvt7g0onUiAj7sjZ/tnKgwoHanNw==";

    private final JwtService jwtService = new JwtService(SECRET, 3600);
    private final UserPrincipal principal = new UserPrincipal(1L, "buyer@example.com", "hash", Role.CUSTOMER);

    @Test
    void generateToken_thenExtractValidSubject_roundTripsTheEmail() {
        String token = jwtService.generateToken(principal);

        assertThat(jwtService.extractValidSubject(token)).isEqualTo("buyer@example.com");
    }

    @Test
    void extractValidSubject_returnsNull_forMalformedToken() {
        assertThat(jwtService.extractValidSubject("not-a-real-jwt")).isNull();
    }

    @Test
    void extractValidSubject_returnsNull_whenSignatureWasTamperedWith() {
        String token = jwtService.generateToken(principal);
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThat(jwtService.extractValidSubject(tampered)).isNull();
    }

    @Test
    void extractValidSubject_returnsNull_forExpiredToken() {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        Instant past = Instant.now().minusSeconds(7200);
        String expiredToken = Jwts.builder()
                .subject("buyer@example.com")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(60)))
                .signWith(key)
                .compact();

        assertThat(jwtService.extractValidSubject(expiredToken)).isNull();
    }

    @Test
    void extractValidSubject_returnsNull_whenSignedWithADifferentSecret() {
        var otherKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                "85sN09upk62ZjPzeIt0T74paUCUzoGvK7Z2yOruLkcmmd7OBfSPmcN29zp3TPywXjFOUnbXVZGtbmc5Pajcz2g=="));
        String tokenFromAnotherIssuer = Jwts.builder()
                .subject("attacker@example.com")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        assertThat(jwtService.extractValidSubject(tokenFromAnotherIssuer)).isNull();
    }
}
