package com.zn.payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.zn.entity.PricingConfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payment_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    // Relationship to PricingConfig
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pricing_config_id")
    private PricingConfig pricingConfig;

    // TODO: Add AccommodationMetadata relationship after fixing compilation issues
    // @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    // @JoinColumn(name = "accommodation_metadata_id")
    // private AccommodationMetadata accommodationMetadata;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        
        // Set default status if not provided
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
        
        // Validate amount matches pricing config if available
        validateAmountWithPricingConfig();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        
        // Validate amount matches pricing config if available
        validateAmountWithPricingConfig();
    }
    
    /**
     * Validates that the payment amount matches the pricing configuration
     */
    private void validateAmountWithPricingConfig() {
        if (pricingConfig != null && pricingConfig.getTotalPrice() != null) {
            BigDecimal expectedAmount = pricingConfig.getTotalPrice();
            
            if (amountTotal != null && expectedAmount.compareTo(amountTotal) != 0) {
                throw new IllegalStateException(
                    String.format("Payment amount (%.2f euros) does not match pricing config total (%.2f euros)", 
                                amountTotal.doubleValue(), expectedAmount.doubleValue()));
            }
        }
    }

    // Enum for payment status
    public enum PaymentStatus {
        PENDING,
        COMPLETED,
        FAILED,
        CANCELLED,
        EXPIRED
    }

    // Factory method to create PaymentRecord from Stripe response data
    public static PaymentRecord fromStripeResponse(String sessionId, String customerEmail, 
                                                 BigDecimal amountTotalEuros, String currency,
                                                 LocalDateTime stripeCreatedAt, 
                                                 LocalDateTime stripeExpiresAt,
                                                 String paymentStatus) {
        return PaymentRecord.builder()
                .sessionId(sessionId)
                .customerEmail(customerEmail)
                .amountTotal(amountTotalEuros)
                .currency(currency)
                .stripeCreatedAt(stripeCreatedAt)
                .stripeExpiresAt(stripeExpiresAt)
                .paymentStatus(paymentStatus)
                .status(PaymentStatus.PENDING)
                .build();
    }

    // Factory method to create PaymentRecord with PricingConfig
    public static PaymentRecord fromPricingConfig(String sessionId, String customerEmail, 
                                                String currency, PricingConfig pricingConfig,
                                                LocalDateTime stripeCreatedAt, 
                                                LocalDateTime stripeExpiresAt,
                                                String paymentStatus) {
        // Use euro amount directly from pricing config
        BigDecimal amountInEuros = pricingConfig.getTotalPrice();
        
        return PaymentRecord.builder()
                .sessionId(sessionId)
                .customerEmail(customerEmail)
                .amountTotal(amountInEuros)
                .currency(currency)
                .stripeCreatedAt(stripeCreatedAt)
                .stripeExpiresAt(stripeExpiresAt)
                .paymentStatus(paymentStatus)
                .pricingConfig(pricingConfig)
                .status(PaymentStatus.PENDING)
                .build();
    }

    // Factory method for creating PaymentRecord with both Stripe session and PricingConfig
    public static PaymentRecord fromStripeWithPricing(String sessionId, String customerEmail,
                                                     String currency, PricingConfig pricingConfig,
                                                     LocalDateTime stripeCreatedAt,
                                                     LocalDateTime stripeExpiresAt,
                                                     String paymentStatus) {
        PaymentRecord record = fromPricingConfig(sessionId, customerEmail, currency, pricingConfig,
                                               stripeCreatedAt, stripeExpiresAt, paymentStatus);
        return record;
    }

    // Method to update from Stripe webhook data
    public void updateFromStripeEvent(String paymentIntentId, String eventStatus) {
        this.paymentIntentId = paymentIntentId;
        
        // Map Stripe event status to our enum
        switch (eventStatus.toLowerCase()) {
            case "complete":
            case "paid":
                this.status = PaymentStatus.COMPLETED;
                break;
            case "expired":
                this.status = PaymentStatus.EXPIRED;
                break;
            case "canceled":
                this.status = PaymentStatus.CANCELLED;
                break;
            default:
                this.status = PaymentStatus.PENDING;
        }
    }

    // Convenience methods
    public boolean isCompleted() {
        return status == PaymentStatus.COMPLETED;
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public boolean isExpired() {
        return status == PaymentStatus.EXPIRED || 
               (stripeExpiresAt != null && stripeExpiresAt.isBefore(LocalDateTime.now()));
    }

    // Format amount for display (already in euros)
    public BigDecimal getAmountInEuros() {
        return amountTotal != null ? amountTotal : BigDecimal.ZERO;
    }
    
    /**
     * Get the expected amount from pricing config in euros
     */
    public BigDecimal getExpectedAmountFromPricing() {
        if (pricingConfig == null || pricingConfig.getTotalPrice() == null) {
            return null;
        }
        return pricingConfig.getTotalPrice();
    }
    
    /**
     * Check if payment amount matches the pricing configuration
     */
    public boolean isAmountMatchingPricing() {
        BigDecimal expectedAmount = getExpectedAmountFromPricing();
        return expectedAmount != null && expectedAmount.compareTo(amountTotal) == 0;
    }
    
    /**
     * Get the pricing config total price in euros
     */
    public double getPricingConfigTotalInDollars() {
        if (pricingConfig == null || pricingConfig.getTotalPrice() == null) {
            return 0.0;
        }
        return pricingConfig.getTotalPrice().doubleValue();
    }
    
    /**
     * Update the payment amount to match the pricing configuration
     * Use this when pricing changes and you need to sync the payment
     */
    public void syncAmountWithPricing() {
        if (pricingConfig != null && pricingConfig.getTotalPrice() != null) {
            this.amountTotal = getExpectedAmountFromPricing();
        }
    }
}
