package com.aman.orderflow.messaging.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventAuditLogRepository extends JpaRepository<EventAuditLog, Long> {

    boolean existsByEventId(String eventId);
}
