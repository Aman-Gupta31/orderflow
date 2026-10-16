package com.aman.orderflow.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Generic "replay the same response for the same Idempotency-Key" helper. Not
 * transactional itself - callers (e.g. PaymentService) are expected to call
 * findExistingReplay/record from within their OWN @Transactional method, so the
 * idempotency record and the business effect it describes commit or roll back
 * together as one unit.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a previously-recorded response for this key, if one exists and the
     * request body matches what was hashed last time. Throws
     * IdempotencyConflictException if the same key is reused for a materially
     * different request - reusing a key is only safe for retrying the exact
     * same request, never for a different one.
     */
    public Optional<ReplayedResponse> findExistingReplay(String idempotencyKey, Object requestBody) {
        String hash = hash(requestBody);
        return repository.findById(idempotencyKey).map(record -> {
            if (!record.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
            }
            return new ReplayedResponse(record.getResponseStatus(), record.getResponseBody());
        });
    }

    public void record(String idempotencyKey, Object requestBody, int responseStatus, Object responseBody) {
        String hash = hash(requestBody);
        repository.save(new IdempotencyRecord(idempotencyKey, hash, responseStatus, toJson(responseBody)));
    }

    private String hash(Object requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(toJson(requestBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on a standard JVM", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize value for idempotency hashing/storage", e);
        }
    }
}
