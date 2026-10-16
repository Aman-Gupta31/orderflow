package com.aman.orderflow.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

// The idempotency key IS the entity's @Id, so JpaRepository's own findById(key)
// already does the lookup this interface would otherwise need a custom method for.
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
