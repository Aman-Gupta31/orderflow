package com.aman.orderflow.payment.repository;

import com.aman.orderflow.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByOrderIdOrderByCreatedAtDesc(Long orderId);
}
