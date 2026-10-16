package com.aman.orderflow.payment;

import com.aman.orderflow.common.idempotency.IdempotencyConflictException;
import com.aman.orderflow.common.idempotency.IdempotencyRecord;
import com.aman.orderflow.common.idempotency.IdempotencyRecordRepository;
import com.aman.orderflow.common.idempotency.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    record SamplePayload(Long orderId, boolean simulateFailure) {
    }

    record SampleResponse(Long paymentId, String status) {
    }

    @Mock
    private IdempotencyRecordRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new IdempotencyService(repository, new ObjectMapper());
    }

    @Test
    void findExistingReplay_returnsEmpty_whenKeyNeverSeenBefore() {
        when(repository.findById("key-1")).thenReturn(Optional.empty());

        var result = service.findExistingReplay("key-1", new SamplePayload(5L, false));

        assertThat(result).isEmpty();
    }

    @Test
    void record_thenFindExistingReplay_returnsTheStoredResponse_forTheSameRequest() {
        var request = new SamplePayload(5L, false);
        var response = new SampleResponse(42L, "SUCCEEDED");

        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        service.record("key-2", request, 201, response);
        verify(repository).save(captor.capture());
        IdempotencyRecord saved = captor.getValue();

        when(repository.findById("key-2")).thenReturn(Optional.of(saved));

        var result = service.findExistingReplay("key-2", request);

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(201);
        assertThat(result.get().body()).contains("\"paymentId\":42").contains("\"status\":\"SUCCEEDED\"");
    }

    @Test
    void findExistingReplay_throwsConflict_whenSameKeyReusedForADifferentRequest() {
        var originalRequest = new SamplePayload(5L, false);

        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        service.record("key-3", originalRequest, 201, new SampleResponse(1L, "SUCCEEDED"));
        verify(repository).save(captor.capture());
        when(repository.findById("key-3")).thenReturn(Optional.of(captor.getValue()));

        var differentRequest = new SamplePayload(6L, false);

        assertThatThrownBy(() -> service.findExistingReplay("key-3", differentRequest))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
