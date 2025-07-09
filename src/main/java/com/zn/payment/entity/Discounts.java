package com.zn.payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.zn.payment.entity.PaymentRecord.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Builder;
import lombok.Data;


@Data
@Builder
@Entity
public class Discounts {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
      private String name;
    private String phone;
   
    private String instituteOrUniversity;
    private String country;

    @Column(unique = true, nullable = false, length = 500)
    private String sessionId;

    @Column(length = 500)
    private String paymentIntentId;

    @Column(nullable = false)
    private String customerEmail;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountTotal; // Amount in euros (e.g., 45.00)

    @Column(length = 3)
    @Builder.Default
    private String currency = "eur";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    // Stripe-specific timestamps (from Stripe response)
    @Column(nullable = false)
    private LocalDateTime stripeCreatedAt;

    private LocalDateTime stripeExpiresAt;

    // System timestamps (for our internal tracking)
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Additional Stripe fields
    @Column(length = 50)
    private String paymentStatus; // Stripe's payment_status


}
