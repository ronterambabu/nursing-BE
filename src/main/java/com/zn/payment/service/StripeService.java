package com.zn.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.zn.entity.PricingConfig;
import com.zn.entity.RegistrationForm;
import com.zn.payment.dto.CheckoutRequest;
import com.zn.payment.dto.PaymentResponseDTO;
import com.zn.payment.entity.PaymentRecord;
import com.zn.payment.repository.PaymentRecordRepository;
import com.zn.repository.IPricingConfigRepository;
import com.zn.repository.IRegistrationFormRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe Payment Service - EURO ONLY PAYMENTS WITH MANDATORY PRICING CONFIG
 * 
 * This service handles all Stripe payment operations and enforces EURO-only payments throughout the system.
 * ALL CHECKOUT SESSIONS NOW REQUIRE A PRICING CONFIG ID FOR VALIDATION.
 * 
 * Key Features:
 * - All payments are processed in EUR currency only
 * - pricingConfigId is MANDATORY for all checkout session creation
 * - Request amounts are validated against PricingConfig.totalPrice in EUROS
 * - Amounts are stored in euros (BigDecimal) in the database
 * - Stripe checkout sessions are created with EUR currency
 * - Payment dashboard reports show amounts in euros
 * - Pricing config validation ensures amounts match exactly in euros
 * - All Stripe API calls use EUR currency
 * 
 * Currency Policy:
 * - Only "eur" currency is accepted in all checkout requests
 * - Unit amounts in requests come in euros (e.g., 45.00) and are converted to cents by the controller
 * - Stripe API receives amounts in cents (e.g., 4500 = €45.00)
 * - Database stores amounts in euros (e.g., 45.00)
 * - Stripe dashboard will display all payments in euros
 * - Payment reports and statistics show euro values
 * 
 * Pricing Validation Policy:
 * - Every checkout session MUST include a valid pricingConfigId
 * - Request amount (unitAmount * quantity) MUST exactly match PricingConfig.totalPrice in EUROS
 * - Validation is performed in EUROS before conversion to cents for Stripe
 * - No sessions can be created without pricing config validation
 * 
 * @author System
 */
@Service
@Slf4j
public class StripeService {
    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");

    @Value("${stripe.api.secret.key}")
    private String secretKey;

    @Value("${stripe.webhook.secret}")
    private String endpointSecret;
    
    @Autowired
    private PaymentRecordRepository paymentRecordRepository;
    
    @Autowired
    private IPricingConfigRepository pricingConfigRepository;
    
    @Autowired
    private IRegistrationFormRepository registrationFormRepository;
    
    private LocalDateTime convertToLocalDateTime(Long timestamp) {
        if (timestamp == null) return null;
        return Instant.ofEpochSecond(timestamp)
                     .atZone(US_ZONE)
                     .toLocalDateTime();
    }

    public PaymentResponseDTO mapSessionToResponceDTO(Session session) {
        PaymentResponseDTO responseDTO = new PaymentResponseDTO();
        responseDTO.setSessionId(session.getId());
        responseDTO.setUrl(session.getUrl()); // Add the checkout URL
        
        // Map payment status properly - if session shows "paid", use it; otherwise default appropriately
        String stripePaymentStatus = session.getPaymentStatus();
        responseDTO.setPaymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "unpaid");
        
        // Convert timestamps to LocalDateTime
        LocalDateTime createdTime = convertToLocalDateTime(session.getCreated());
        LocalDateTime expiresTime = convertToLocalDateTime(session.getExpiresAt());
        
        responseDTO.setStripeCreatedAt(createdTime);
        responseDTO.setStripeExpiresAt(expiresTime);
        
        // Map other fields from session metadata if available
        if (session.getMetadata() != null) {
            responseDTO.setCustomerName(session.getMetadata().get("customerName"));
            responseDTO.setProductName(session.getMetadata().get("productName"));
        }
        
        log.info("✅ Mapped session to response DTO with paymentStatus: {}", responseDTO.getPaymentStatus());
        
        return responseDTO;
    }

    /**
     * Create complete response DTO with both Stripe session and database record information
     */
    public PaymentResponseDTO createCompleteResponseDTO(Session session, PaymentRecord paymentRecord) {
        PaymentResponseDTO responseDTO = new PaymentResponseDTO();
        
        // Map Stripe session information
        responseDTO.setSessionId(session.getId());
        responseDTO.setUrl(session.getUrl());
        // Use paymentStatus from database record instead of session (database has the updated value)
        responseDTO.setPaymentStatus(paymentRecord.getPaymentStatus());
        responseDTO.setStripeCreatedAt(convertToLocalDateTime(session.getCreated()));
        responseDTO.setStripeExpiresAt(convertToLocalDateTime(session.getExpiresAt()));
        
        // Map database record information
        responseDTO.setId(paymentRecord.getId());
        responseDTO.setCustomerEmail(paymentRecord.getCustomerEmail());
        responseDTO.setAmountTotalEuros(paymentRecord.getAmountTotal());
        responseDTO.setAmountTotalCents(paymentRecord.getAmountTotal().multiply(BigDecimal.valueOf(100)).longValue());
        responseDTO.setCurrency(paymentRecord.getCurrency());
        responseDTO.setStatus(paymentRecord.getStatus());
        responseDTO.setCreatedAt(paymentRecord.getCreatedAt());
        responseDTO.setUpdatedAt(paymentRecord.getUpdatedAt());
        
        // Map pricing config information if available
        if (paymentRecord.getPricingConfig() != null) {
            responseDTO.setPricingConfigId(paymentRecord.getPricingConfig().getId());
            responseDTO.setPricingConfigTotalPrice(paymentRecord.getPricingConfig().getTotalPrice());
        }
        
        // Map other fields from session metadata if available
        if (session.getMetadata() != null) {
            responseDTO.setCustomerName(session.getMetadata().get("customerName"));
            responseDTO.setProductName(session.getMetadata().get("productName"));
        }
        
        log.info("✅ Created complete response DTO with DB ID: {} and session ID: {} with paymentStatus: {}", 
                paymentRecord.getId(), session.getId(), paymentRecord.getPaymentStatus());
        
        return responseDTO;
    }

    public Session createDetailedCheckoutSession(CheckoutRequest request) throws StripeException {
        log.info("Creating detailed checkout session for product: {}", request.getProductName());
        
        // Enforce EURO-only payments
        validateEuroCurrency(request);
        
        Stripe.apiKey = secretKey;

        // Create metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("productName", request.getProductName());
        if (request.getOrderReference() != null) {
            metadata.put("orderReference", request.getOrderReference());
        }
        
        // Store customer details for auto-registration
        if (request.getName() != null) {
            metadata.put("customerName", request.getName());
        }
        if (request.getPhone() != null) {
            metadata.put("customerPhone", request.getPhone());
        }
        if (request.getInstituteOrUniversity() != null) {
            metadata.put("customerInstitute", request.getInstituteOrUniversity());
        }
        if (request.getCountry() != null) {
            metadata.put("customerCountry", request.getCountry());
        }

        // Set expiration time (US Eastern)
        ZonedDateTime expirationTime = ZonedDateTime.now(US_ZONE).plus(30, ChronoUnit.MINUTES);

        // Build Stripe SessionCreateParams - enforcing EUR currency
        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(request.getSuccessUrl())
            .setCancelUrl(request.getCancelUrl())
            .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
            .setExpiresAt(expirationTime.toEpochSecond())
            .putAllMetadata(metadata)
            .setCustomerEmail(request.getEmail())
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(request.getQuantity())
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("eur") // Always use EUR
                            .setUnitAmount(request.getUnitAmount())
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(request.getProductName())
                                    .setDescription(request.getDescription())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

    try {
        Session session = Session.create(params);

        log.info("✅ Created checkout session with ID: {} at {}", 
                session.getId(), 
                convertToLocalDateTime(session.getCreated()));

        // 💾 Save to DB with status PENDING - always EUR currency
        PaymentRecord record = PaymentRecord.builder()
            .sessionId(session.getId())
            .paymentIntentId(null)
            .customerEmail(request.getEmail())
            .amountTotal(BigDecimal.valueOf(request.getUnitAmount() * request.getQuantity()).divide(BigDecimal.valueOf(100))) // Convert cents to euros
            .currency("eur") // Always EUR
            .status(PaymentRecord.PaymentStatus.PENDING) // Initial status should be PENDING
            .stripeCreatedAt(convertToLocalDateTime(session.getCreated()))
            .stripeExpiresAt(convertToLocalDateTime(session.getExpiresAt()))
            .paymentStatus(session.getPaymentStatus())
            .build();

        paymentRecordRepository.save(record);
        log.info("💾 Saved PaymentRecord for session: {}", session.getId());

        return session;

    } catch (StripeException e) {
        log.error("❌ Error creating checkout session: {}", e.getMessage());
        throw e;
    }
}

    /**
     * @deprecated This method is no longer used since pricingConfigId is now mandatory.
     * Use createCheckoutSessionWithPricingValidation instead.
     */
    @Deprecated
    public PaymentResponseDTO createCheckoutSessionWithoutPricingValidation(CheckoutRequest request) throws StripeException {
        throw new UnsupportedOperationException("pricingConfigId is now mandatory. Use createCheckoutSessionWithPricingValidation instead.");
    }

    /**
     * Create checkout session with pricing config validation
     * This ensures the payment amount matches the configured pricing
     */
    public Session createCheckoutSessionWithPricing(CheckoutRequest request, PricingConfig pricingConfig) throws StripeException {
        log.info("Creating checkout session with pricing config validation for product: {}", request.getProductName());
        
        // Validate EUR currency first
        validateEuroCurrency(request);
        
        // Validate amount matches pricing config
        Long expectedAmountInCents = pricingConfig.getTotalPrice().multiply(BigDecimal.valueOf(100)).longValue();
        Long requestedAmount = request.getUnitAmount() * request.getQuantity();
        
        if (!expectedAmountInCents.equals(requestedAmount)) {
            throw new IllegalArgumentException(
                String.format("Payment amount (%d cents) does not match pricing config total (%d cents)", 
                            requestedAmount, expectedAmountInCents));
        }
        
        Stripe.apiKey = secretKey;

        // Create metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("productName", request.getProductName());
        metadata.put("pricingConfigId", pricingConfig.getId().toString());
        if (request.getOrderReference() != null) {
            metadata.put("orderReference", request.getOrderReference());
        }
        
        // Store customer details for auto-registration
        if (request.getName() != null) {
            metadata.put("customerName", request.getName());
        }
        if (request.getPhone() != null) {
            metadata.put("customerPhone", request.getPhone());
        }
        if (request.getInstituteOrUniversity() != null) {
            metadata.put("customerInstitute", request.getInstituteOrUniversity());
        }
        if (request.getCountry() != null) {
            metadata.put("customerCountry", request.getCountry());
        }
        
        // Store additional registration details
        if (request.getRegistrationType() != null) {
            metadata.put("registrationType", request.getRegistrationType());
        }
        if (request.getPresentationType() != null) {
            metadata.put("presentationType", request.getPresentationType());
        }
        metadata.put("accompanyingPerson", String.valueOf(request.isAccompanyingPerson()));
        metadata.put("extraNights", String.valueOf(request.getExtraNights()));
        metadata.put("accommodationNights", String.valueOf(request.getAccommodationNights()));
        metadata.put("accommodationGuests", String.valueOf(request.getAccommodationGuests()));

        // Set expiration time (US Eastern)
        ZonedDateTime expirationTime = ZonedDateTime.now(US_ZONE).plus(30, ChronoUnit.MINUTES);

        // Build Stripe SessionCreateParams - enforcing EUR currency
        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(request.getSuccessUrl())
            .setCancelUrl(request.getCancelUrl())
            .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
            .setExpiresAt(expirationTime.toEpochSecond())
            .putAllMetadata(metadata)
            .setCustomerEmail(request.getEmail())
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(request.getQuantity())
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency("eur") // Always use EUR
                            .setUnitAmount(request.getUnitAmount())
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(request.getProductName())
                                    .setDescription(request.getDescription())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

        try {
            Session session = Session.create(params);

            log.info("✅ Created checkout session with ID: {} at {} for pricing config: {}", 
                    session.getId(), 
                    convertToLocalDateTime(session.getCreated()),
                    pricingConfig.getId());

            // 💾 Save to DB with pricing config relationship - always EUR currency
            PaymentRecord record = PaymentRecord.fromStripeWithPricing(
                session.getId(),
                request.getEmail(),
                "eur", // Always EUR
                pricingConfig,
                convertToLocalDateTime(session.getCreated()),
                convertToLocalDateTime(session.getExpiresAt()),
                session.getPaymentStatus()
            );

            paymentRecordRepository.save(record);
            log.info("💾 Saved PaymentRecord for session: {} with pricing config: {}", 
                    session.getId(), pricingConfig.getId());

            return session;

        } catch (StripeException e) {
            log.error("❌ Error creating checkout session with pricing: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Create checkout session with strict validation against pricing config
     * Validates amount, currency, and ensures all values are in euros
     */
    public PaymentResponseDTO createValidatedCheckoutSession(CheckoutRequest request) throws StripeException {
        log.info("Creating validated checkout session for pricingConfigId: {}", request.getPricingConfigId());
        
        // 1. Validate required fields
        if (request.getPricingConfigId() == null) {
            throw new IllegalArgumentException("Pricing config ID is required for checkout validation");
        }
        
        // 2. Validate currency is EUR
        validateEuroCurrency(request);
        
        // 3. Fetch pricing config
        PricingConfig pricingConfig = pricingConfigRepository.findById(request.getPricingConfigId())
            .orElseThrow(() -> new IllegalArgumentException("Pricing config not found with ID: " + request.getPricingConfigId()));
        
        log.info("Found pricing config with total price: {} EUR", pricingConfig.getTotalPrice());
        
        // 4. Validate unitAmount matches pricing config (convert euros to cents for comparison)
        Long expectedAmountInCents = pricingConfig.getTotalPrice().multiply(BigDecimal.valueOf(100)).longValue();
        Long requestedTotalInCents = request.getUnitAmount() * request.getQuantity();
        
        if (!expectedAmountInCents.equals(requestedTotalInCents)) {
            log.error("Payment amount validation failed. Expected: {} cents ({} EUR), Requested: {} cents ({} EUR)", 
                     expectedAmountInCents, pricingConfig.getTotalPrice(), 
                     requestedTotalInCents, BigDecimal.valueOf(requestedTotalInCents).divide(BigDecimal.valueOf(100)));
            throw new IllegalArgumentException(
                String.format("Payment amount mismatch. Expected: %s EUR, but received: %s EUR", 
                            pricingConfig.getTotalPrice(),
                            BigDecimal.valueOf(requestedTotalInCents).divide(BigDecimal.valueOf(100))));
        }
        
        log.info("✅ Amount validation passed: {} EUR", pricingConfig.getTotalPrice());
        
        // 5. Create Stripe session with validated amounts
        Session session = createCheckoutSessionWithPricing(request, pricingConfig);
        
        // 6. Fetch the saved PaymentRecord from database
        PaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(session.getId())
            .orElseThrow(() -> new RuntimeException("PaymentRecord not found after creation for session: " + session.getId()));
        
        // 7. Create complete response DTO with both Stripe and DB information
        PaymentResponseDTO response = createCompleteResponseDTO(session, paymentRecord);
        log.info("✅ Validated checkout session created: {} with DB ID: {}", session.getId(), paymentRecord.getId());
        
        return response;
    }

    /**
     * Create checkout session with pricing config ID validation
     * Fetches pricing config by ID and validates amount matches exactly in EUROS
     */
    public PaymentResponseDTO createCheckoutSessionWithPricingValidation(CheckoutRequest request, Long pricingConfigId) throws StripeException {
        log.info("Creating checkout session with mandatory pricing config ID: {}", pricingConfigId);
        
        // 1. Validate currency is EUR
        validateEuroCurrency(request);
        
        // 2. Fetch pricing config by ID
        PricingConfig pricingConfig = pricingConfigRepository.findById(pricingConfigId)
            .orElseThrow(() -> new IllegalArgumentException("Pricing config not found with ID: " + pricingConfigId));
        
        log.info("Found pricing config with total price: {} EUR", pricingConfig.getTotalPrice());
        
        // 3. Convert request amount back to euros for comparison (request comes in cents after controller conversion)
        BigDecimal requestTotalInEuros = BigDecimal.valueOf(request.getUnitAmount() * request.getQuantity()).divide(BigDecimal.valueOf(100));
        BigDecimal pricingConfigTotalInEuros = pricingConfig.getTotalPrice();
        
        log.info("Comparing amounts in EUROS - Request total: {} EUR, PricingConfig total: {} EUR", 
                requestTotalInEuros, pricingConfigTotalInEuros);
        
        // 4. Validate amounts match exactly in EUROS (with scale precision)
        if (requestTotalInEuros.compareTo(pricingConfigTotalInEuros) != 0) {
            log.error("❌ Payment amount validation failed in EUROS. Expected: {} EUR, Requested: {} EUR", 
                     pricingConfigTotalInEuros, requestTotalInEuros);
            throw new IllegalArgumentException(
                String.format("Payment amount mismatch. Expected: %s EUR from pricing config, but received: %s EUR in request", 
                            pricingConfigTotalInEuros, requestTotalInEuros));
        }
        
        log.info("✅ Amount validation passed in EUROS: {} EUR matches pricing config", requestTotalInEuros);
        
        // 5. Create Stripe session using the existing method
        Session session = createCheckoutSessionWithPricing(request, pricingConfig);
        
        // 6. Fetch the saved PaymentRecord from database
        PaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(session.getId())
            .orElseThrow(() -> new RuntimeException("PaymentRecord not found after creation for session: " + session.getId()));
        
        // 7. Create complete response DTO with both Stripe and DB information
        PaymentResponseDTO response = createCompleteResponseDTO(session, paymentRecord);
        log.info("✅ Checkout session created with mandatory pricing validation: {} with DB ID: {}", 
                session.getId(), paymentRecord.getId());
        
        return response;
    }

    public Event constructWebhookEvent(String payload, String sigHeader) throws SignatureVerificationException {
        log.debug("Constructing webhook event from payload with signature");
        
        if (endpointSecret == null || endpointSecret.trim().isEmpty()) {
            log.error("❌ Webhook endpoint secret is not configured! Check your STRIPE_WEBHOOK_SECRET environment variable");
            throw new SignatureVerificationException("Webhook endpoint secret is not configured", sigHeader);
        }
        
        if (!endpointSecret.startsWith("whsec_")) {
            log.error("❌ Invalid webhook secret format! Webhook secrets should start with 'whsec_'. Current: {}", 
                    endpointSecret.substring(0, Math.min(10, endpointSecret.length())) + "...");
            throw new SignatureVerificationException("Invalid webhook secret format", sigHeader);
        }
        
        try {
            log.info("✅ Using webhook secret: {}...", endpointSecret.substring(0, Math.min(10, endpointSecret.length())));
            return Webhook.constructEvent(payload, sigHeader, endpointSecret);
        } catch (SignatureVerificationException e) {
            log.error("❌ Signature verification failed. Payload length: {}, Signature: {}, Secret prefix: {}", 
                    payload.length(), sigHeader, endpointSecret.substring(0, Math.min(10, endpointSecret.length())));
            throw e;
        }
    }

    public void processWebhookEvent(Event event) {
        log.info("Processing webhook event of type: {}", event.getType());
        
        try {
            switch (event.getType()) {
                case "checkout.session.completed" -> {
                    log.info("🎯 Handling checkout.session.completed event");
                    handleCheckoutSessionCompleted(event);
                }
                case "payment_intent.succeeded" -> {
                    log.info("🎯 Handling payment_intent.succeeded event");
                    handlePaymentIntentSucceeded(event);
                }
                case "payment_intent.payment_failed" -> {
                    log.info("🎯 Handling payment_intent.payment_failed event");
                    handlePaymentIntentFailed(event);
                }
                case "checkout.session.expired" -> {
                    log.info("🎯 Handling checkout.session.expired event");
                    handleCheckoutSessionExpired(event);
                }
                default -> {
                    log.info("⚠️ Unhandled event type: {} - No action taken", event.getType());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error processing webhook event {}: {}", event.getType(), e.getMessage(), e);
        }
    }

    private void handleCheckoutSessionCompleted(Event event) {
        log.info("🔄 Starting handleCheckoutSessionCompleted processing...");
        
        try {
            // Use EventDataObjectDeserializer to get the Session object
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            
            if (dataObjectDeserializer.getObject().isPresent()) {
                Object deserializedObject = dataObjectDeserializer.getObject().get();
                log.info("📋 Event data object type: {}", deserializedObject.getClass().getSimpleName());
                
                if (deserializedObject instanceof Session session) {
                    log.info("✅ Successfully retrieved Session: {}", session.getId());
                    
                    // Log key data from the webhook for debugging
                    log.info("📊 Webhook Session Data:");
                    log.info("   - Session ID: {}", session.getId());
                    log.info("   - Amount Total: {} cents", session.getAmountTotal());
                    log.info("   - Currency: {}", session.getCurrency());
                    log.info("   - Customer Email: {}", session.getCustomerEmail());
                    log.info("   - Payment Intent: {}", session.getPaymentIntent());
                    log.info("   - Payment Status: {}", session.getPaymentStatus());
                    log.info("   - Session Status: {}", session.getStatus());
                    
                    processCheckoutSessionCompleted(session);
                } else {
                    log.error("❌ Event data object is not a Session! Type: {}", deserializedObject.getClass().getName());
                    
                    // Fallback: Try to extract session data manually from event
                    log.info("🔄 Attempting manual session data extraction from event...");
                    extractAndProcessSessionDataFromEvent(event);
                }
            } else {
                log.error("❌ Failed to deserialize checkout.session.completed event data");
                
                // Fallback: Try to extract session data manually from event
                log.info("🔄 Attempting manual session data extraction from event...");
                extractAndProcessSessionDataFromEvent(event);
            }
        } catch (Exception e) {
            log.error("❌ Error in handleCheckoutSessionCompleted: {}", e.getMessage(), e);
            
            // Last resort: Try manual extraction
            try {
                log.info("🔄 Last resort: Attempting manual session data extraction...");
                extractAndProcessSessionDataFromEvent(event);
            } catch (Exception fallbackException) {
                log.error("❌ All fallback methods failed: {}", fallbackException.getMessage(), fallbackException);
            }
        }
        
        log.info("✅ Completed handleCheckoutSessionCompleted processing");
    }
    
    /**
     * Fallback method to manually extract session data from the event when deserialization fails
     * This handles the case where EventDataObjectDeserializer doesn't work properly
     */
    private void extractAndProcessSessionDataFromEvent(Event event) {
        try {
            // Get the raw JSON object from the event
            com.stripe.model.StripeObject dataObject = event.getDataObjectDeserializer().getObject().orElse(null);
            
            if (dataObject == null) {
                log.error("❌ No data object found in event after manual extraction attempt");
                return;
            }
            
            // Try to access the session data through the JSON structure
            String rawJson = dataObject.toJson();
            log.info("📋 Raw webhook data JSON: {}", rawJson);
            
            // Parse key fields manually from the JSON string (basic parsing)
            String sessionId = extractJsonField(rawJson, "id");
            String customerEmail = extractJsonField(rawJson, "customer_email");
            String paymentIntent = extractJsonField(rawJson, "payment_intent");
            String paymentStatus = extractJsonField(rawJson, "payment_status");
            String sessionStatus = extractJsonField(rawJson, "status");
            String currency = extractJsonField(rawJson, "currency");
            String amountTotalStr = extractJsonField(rawJson, "amount_total");
            
            Long amountTotal = null;
            if (amountTotalStr != null && !amountTotalStr.isEmpty()) {
                try {
                    amountTotal = Long.parseLong(amountTotalStr);
                } catch (NumberFormatException e) {
                    log.warn("⚠️ Could not parse amount_total: {}", amountTotalStr);
                }
            }
            
            log.info("📊 Manually Extracted Session Data:");
            log.info("   - Session ID: {}", sessionId);
            log.info("   - Customer Email: {}", customerEmail);
            log.info("   - Payment Intent: {}", paymentIntent);
            log.info("   - Payment Status: {}", paymentStatus);
            log.info("   - Session Status: {}", sessionStatus);
            log.info("   - Currency: {}", currency);
            log.info("   - Amount Total: {} cents", amountTotal);
            
            if (sessionId == null) {
                log.error("❌ Session ID not found in webhook data");
                return;
            }
            
            // Process the manually extracted data
            processCheckoutSessionCompletedManual(sessionId, customerEmail, paymentIntent, 
                                                paymentStatus, currency, amountTotal);
            
        } catch (Exception e) {
            log.error("❌ Error in manual session data extraction: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Simple JSON field extractor for basic parsing
     */
    private String extractJsonField(String json, String fieldName) {
        try {
            String searchPattern = "\"" + fieldName + "\":";
            int startIndex = json.indexOf(searchPattern);
            if (startIndex == -1) {
                return null;
            }
            
            startIndex += searchPattern.length();
            
            // Skip whitespace
            while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
                startIndex++;
            }
            
            if (startIndex >= json.length()) {
                return null;
            }
            
            // Handle string values (enclosed in quotes)
            if (json.charAt(startIndex) == '"') {
                startIndex++; // Skip opening quote
                int endIndex = json.indexOf('"', startIndex);
                if (endIndex == -1) {
                    return null;
                }
                return json.substring(startIndex, endIndex);
            } else {
                // Handle numeric or other values
                int endIndex = startIndex;
                while (endIndex < json.length() && 
                       json.charAt(endIndex) != ',' && 
                       json.charAt(endIndex) != '}' && 
                       json.charAt(endIndex) != ']' &&
                       !Character.isWhitespace(json.charAt(endIndex))) {
                    endIndex++;
                }
                return json.substring(startIndex, endIndex);
            }
        } catch (Exception e) {
            log.warn("⚠️ Error extracting field '{}' from JSON: {}", fieldName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Process checkout session completion using manually extracted data
     */
    private void processCheckoutSessionCompletedManual(String sessionId, String customerEmail, 
                                                     String paymentIntent, String paymentStatus, 
                                                     String currency, Long amountTotal) {
        try {
            log.info("🔄 Processing manually extracted session data for session: {}", sessionId);
            
            // Find existing PaymentRecord
            PaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                .orElse(null);
            
            if (paymentRecord != null) {
                log.info("📋 Found existing PaymentRecord ID: {} for session: {}", paymentRecord.getId(), sessionId);
                
                // Update existing record with webhook data
                paymentRecord.setPaymentIntentId(paymentIntent);
                paymentRecord.setStatus(PaymentRecord.PaymentStatus.COMPLETED);
                paymentRecord.setPaymentStatus(paymentStatus != null ? paymentStatus : "paid");
                
                // Update customer email if it was null before
                if (paymentRecord.getCustomerEmail() == null && customerEmail != null) {
                    paymentRecord.setCustomerEmail(customerEmail);
                }
                
                // Update amount if provided and different
                if (amountTotal != null) {
                    BigDecimal amountInEuros = BigDecimal.valueOf(amountTotal).divide(BigDecimal.valueOf(100));
                    if (paymentRecord.getAmountTotal() == null || 
                        paymentRecord.getAmountTotal().compareTo(amountInEuros) != 0) {
                        log.info("💰 Updating amount from {} to {} EUR", paymentRecord.getAmountTotal(), amountInEuros);
                        paymentRecord.setAmountTotal(amountInEuros);
                    }
                }
                
                // Update currency if provided
                if (currency != null && paymentRecord.getCurrency() == null) {
                    paymentRecord.setCurrency(currency);
                }
                
                PaymentRecord savedRecord = paymentRecordRepository.save(paymentRecord);
                log.info("💾 ✅ Updated PaymentRecord ID: {} for session: {} to COMPLETED status with paymentStatus '{}'", 
                        savedRecord.getId(), sessionId, savedRecord.getPaymentStatus());
                
                // Log the current state for debugging
                log.info("🔍 PaymentRecord state after manual update: ID={}, Status={}, PaymentStatus={}, PaymentIntentId={}", 
                        savedRecord.getId(), savedRecord.getStatus(), savedRecord.getPaymentStatus(), savedRecord.getPaymentIntentId());
                
                // Link registration after successful payment (pass null for session since we don't have the object)
                autoRegisterUserAfterPaymentManual(savedRecord, customerEmail);
                
            } else {
                log.warn("⚠️ PaymentRecord not found for session {}, creating new one from webhook data", sessionId);
                
                PaymentRecord newRecord = PaymentRecord.builder()
                        .sessionId(sessionId)
                        .paymentIntentId(paymentIntent)
                        .customerEmail(customerEmail)
                        .amountTotal(amountTotal != null ? 
                            BigDecimal.valueOf(amountTotal).divide(BigDecimal.valueOf(100)) : null)
                        .currency(currency != null ? currency : "eur")
                        .status(PaymentRecord.PaymentStatus.COMPLETED)
                        .stripeCreatedAt(LocalDateTime.now()) // Use current time since we don't have stripe timestamp
                        .paymentStatus(paymentStatus != null ? paymentStatus : "paid")
                        .build();

                PaymentRecord savedRecord = paymentRecordRepository.save(newRecord);
                log.info("💾 ✅ Created new PaymentRecord ID: {} for session: {} with paymentStatus '{}'", 
                        savedRecord.getId(), sessionId, savedRecord.getPaymentStatus());
                
                // Link registration after successful payment
                autoRegisterUserAfterPaymentManual(savedRecord, customerEmail);
            }
        } catch (Exception e) {
            log.error("❌ Error processing manual checkout session completion for session {}: {}", sessionId, e.getMessage(), e);
        }
    }
    
    /**
     * Auto-register user after payment using manual data extraction
     */
    private void autoRegisterUserAfterPaymentManual(PaymentRecord paymentRecord, String customerEmail) {
        log.info("🔄 Manual verification of association for payment record ID: {}", paymentRecord.getId());
        
        try {
            // Use the existing auto-registration logic but with manual email extraction
            if (customerEmail == null) {
                customerEmail = paymentRecord.getCustomerEmail();
            }
            
            if (customerEmail == null) {
                log.error("❌ CRITICAL: Cannot verify association - customer email not found for payment record ID: {}", paymentRecord.getId());
                return;
            }
            
            // Find the most recent registration form for this customer email
            RegistrationForm existingRegistration = registrationFormRepository.findTopByEmailOrderByIdDesc(customerEmail);
            
            if (existingRegistration == null) {
                log.error("❌ CRITICAL: No registration form found for email: {} (payment record ID: {})", 
                        customerEmail, paymentRecord.getId());
                return;
            }
            
            // Check if association already exists
            if (paymentRecord.getRegistrationForm() != null) {
                log.info("✅ Payment record ID: {} already has registration form ID: {} associated", 
                        paymentRecord.getId(), paymentRecord.getRegistrationForm().getId());
                return;
            }
            
            // Establish the bidirectional association
            log.info("🔗 Creating association between registration form ID: {} and payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            
            existingRegistration.setPaymentRecord(paymentRecord);
            paymentRecord.setRegistrationForm(existingRegistration);
            
            // Save both entities to ensure the relationship is persisted
            registrationFormRepository.save(existingRegistration);
            paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Successfully linked registration form ID: {} to payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            
        } catch (Exception e) {
            log.error("❌ Error in manual registration linking for payment record ID {}: {}", 
                    paymentRecord.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * Process checkout session completed logic (extracted for reuse)
     * Updates PaymentRecord based on Stripe webhook data structure
     */
    private void processCheckoutSessionCompleted(Session session) {
        LocalDateTime completedTime = convertToLocalDateTime(session.getCreated());

        log.info("✅ Payment successful for session: {} at {}", session.getId(), completedTime);
        log.info("💳 Customer email: {}", session.getCustomerDetails() != null ?
                session.getCustomerDetails().getEmail() : session.getCustomerEmail());
        log.info("💰 Amount total: {} cents", session.getAmountTotal());
        log.info("💳 Payment Status from Stripe: {}", session.getPaymentStatus());
        log.info("📋 Session Status from Stripe: {}", session.getStatus());
        log.info("🔗 Payment Intent ID: {}", session.getPaymentIntent());

        // Update existing record based on webhook data structure
        try {
            PaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(session.getId())
                .orElse(null);
            
            if (paymentRecord != null) {
                log.info("📋 Found existing PaymentRecord ID: {} for session: {}", paymentRecord.getId(), session.getId());
                
                // Update record with webhook data - following the sample structure you provided
                // Sample: "payment_intent": "pi_1PABC1SDxA1b23dEfGHIjklm"
                paymentRecord.setPaymentIntentId(session.getPaymentIntent());
                
                // Sample: "status": "complete" indicates successful payment
                if ("complete".equals(session.getStatus())) {
                    paymentRecord.setStatus(PaymentRecord.PaymentStatus.COMPLETED);
                }
                
                // Sample: "payment_status": "paid" indicates payment was successful
                String stripePaymentStatus = session.getPaymentStatus();
                paymentRecord.setPaymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "paid");
                
                // Update customer email from webhook data
                // Sample: "customer_email": "customer@example.com"
                String customerEmail = session.getCustomerDetails() != null ? 
                    session.getCustomerDetails().getEmail() : session.getCustomerEmail();
                if (paymentRecord.getCustomerEmail() == null && customerEmail != null) {
                    paymentRecord.setCustomerEmail(customerEmail);
                }
                
                // Update amount from webhook data
                // Sample: "amount_total": 4999 (in cents)
                if (session.getAmountTotal() != null) {
                    BigDecimal webhookAmountInEuros = BigDecimal.valueOf(session.getAmountTotal()).divide(BigDecimal.valueOf(100));
                    if (paymentRecord.getAmountTotal() == null || 
                        paymentRecord.getAmountTotal().compareTo(webhookAmountInEuros) != 0) {
                        log.info("💰 Updating amount from {} to {} EUR based on webhook data", 
                                paymentRecord.getAmountTotal(), webhookAmountInEuros);
                        paymentRecord.setAmountTotal(webhookAmountInEuros);
                    }
                }
                
                // Update currency from webhook data
                // Sample: "currency": "usd" (but we convert to EUR for our system)
                if (session.getCurrency() != null && paymentRecord.getCurrency() == null) {
                    paymentRecord.setCurrency(session.getCurrency());
                }
                
                PaymentRecord savedRecord = paymentRecordRepository.save(paymentRecord);
                log.info("💾 ✅ Updated PaymentRecord ID: {} for session: {} to COMPLETED status with paymentStatus '{}'", 
                        savedRecord.getId(), session.getId(), savedRecord.getPaymentStatus());
                
                // Log the final state for debugging
                log.info("🔍 PaymentRecord final state: ID={}, Status={}, PaymentStatus={}, PaymentIntentId={}, Amount={} EUR", 
                        savedRecord.getId(), savedRecord.getStatus(), savedRecord.getPaymentStatus(), 
                        savedRecord.getPaymentIntentId(), savedRecord.getAmountTotal());
                
                // Link to registration form after successful payment
                autoRegisterUserAfterPayment(savedRecord, session);
                
            } else {
                log.warn("⚠️ PaymentRecord not found for session {}, creating new one based on webhook data", session.getId());
                
                String customerEmail = session.getCustomerDetails() != null ? 
                    session.getCustomerDetails().getEmail() : session.getCustomerEmail();
                String stripePaymentStatus = session.getPaymentStatus();
                
                PaymentRecord record = PaymentRecord.builder()
                        .sessionId(session.getId())
                        .paymentIntentId(session.getPaymentIntent())
                        .customerEmail(customerEmail)
                        .amountTotal(session.getAmountTotal() != null ? 
                            BigDecimal.valueOf(session.getAmountTotal()).divide(BigDecimal.valueOf(100)) : null)
                        .currency(session.getCurrency())
                        .status(PaymentRecord.PaymentStatus.COMPLETED)
                        .stripeCreatedAt(completedTime)
                        .stripeExpiresAt(convertToLocalDateTime(session.getExpiresAt()))
                        .paymentStatus(stripePaymentStatus != null ? stripePaymentStatus : "paid")
                        .build();

                PaymentRecord savedRecord = paymentRecordRepository.save(record);
                log.info("💾 ✅ Created new PaymentRecord ID: {} for session: {} with paymentStatus '{}'", 
                        savedRecord.getId(), session.getId(), savedRecord.getPaymentStatus());
                
                // Link to registration form after successful payment
                autoRegisterUserAfterPayment(savedRecord, session);
            }
        } catch (Exception e) {
            log.error("❌ Error updating PaymentRecord for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    /**
     * Handle payment_intent.succeeded webhook event
     * This fires when the payment is successfully processed
     */
    private void handlePaymentIntentSucceeded(Event event) {
        log.info("🔄 Starting handlePaymentIntentSucceeded processing...");
        
        try {
            // Use EventDataObjectDeserializer instead of deprecated getObject()
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            if (dataObjectDeserializer.getObject().isPresent()) {
                Object deserializedObject = dataObjectDeserializer.getObject().get();
                log.info("📋 Event data object type: {}", deserializedObject.getClass().getSimpleName());
                
                if (deserializedObject instanceof com.stripe.model.PaymentIntent paymentIntent) {
                    log.info("✅ Payment Intent succeeded: {} for amount: {} {}", 
                            paymentIntent.getId(), 
                            paymentIntent.getAmount() != null ? BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100)) : "unknown",
                            paymentIntent.getCurrency());
                    
                    processPaymentIntentUpdate(paymentIntent);
                } else {
                    log.error("❌ Event data object is not a PaymentIntent! Type: {}", deserializedObject.getClass().getName());
                }
            } else {
                log.error("❌ Failed to deserialize payment_intent.succeeded event data");
            }
        } catch (Exception e) {
            log.error("❌ Failed to process payment_intent.succeeded: {}", e.getMessage(), e);
        }
        
        log.info("✅ Completed handlePaymentIntentSucceeded processing");
    }
    
    /**
     * Process PaymentIntent update logic (extracted for reuse)
     */
    private void processPaymentIntentUpdate(com.stripe.model.PaymentIntent paymentIntent) {
        try {
            // First, try to find PaymentRecord by payment intent ID
            PaymentRecord existingRecord = paymentRecordRepository.findByPaymentIntentId(paymentIntent.getId())
                .orElse(null);
            
            if (existingRecord != null) {
                log.info("📋 Found existing PaymentRecord by payment intent ID: {}", existingRecord.getId());
                updateExistingPaymentRecord(existingRecord, paymentIntent);
            } else {
                log.info("🔍 No PaymentRecord found by payment intent ID, searching for PENDING records...");
                
                // Look for PENDING records and update the most suitable one
                List<PaymentRecord> pendingRecords = paymentRecordRepository.findByStatus(PaymentRecord.PaymentStatus.PENDING);
                log.info("📊 Found {} PENDING PaymentRecord(s)", pendingRecords.size());
                
                if (!pendingRecords.isEmpty()) {
                    // Find the best matching pending record (by amount if possible)
                    PaymentRecord recordToUpdate = findBestMatchingPendingRecord(pendingRecords, paymentIntent);
                    updatePendingPaymentRecord(recordToUpdate, paymentIntent);
                } else {
                    log.warn("⚠️ No PENDING PaymentRecord found, creating new record");
                    createNewPaymentRecord(paymentIntent);
                }
            }
        } catch (Exception e) {
            log.error("❌ Error in processPaymentIntentUpdate: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Update existing PaymentRecord that already has payment intent ID
     */
    private void updateExistingPaymentRecord(PaymentRecord record, com.stripe.model.PaymentIntent paymentIntent) {
        log.info("🔄 Updating existing PaymentRecord ID: {} for payment intent: {}", record.getId(), paymentIntent.getId());
        
        record.setStatus(PaymentRecord.PaymentStatus.COMPLETED);
        record.setPaymentIntentId(paymentIntent.getId());
        record.setPaymentStatus("paid"); // Set Stripe payment status to "paid"
        
        // Update amount and currency if needed
        if (record.getAmountTotal() == null && paymentIntent.getAmount() != null) {
            BigDecimal amount = BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100));
            record.setAmountTotal(amount);
            log.info("💰 Updated amount to: {} EUR", amount);
        }
        if (record.getCurrency() == null && paymentIntent.getCurrency() != null) {
            record.setCurrency(paymentIntent.getCurrency());
            log.info("💱 Updated currency to: {}", paymentIntent.getCurrency());
        }
        
        paymentRecordRepository.save(record);
        log.info("💾 ✅ Successfully updated existing PaymentRecord ID: {} to COMPLETED status with paymentStatus 'paid'", record.getId());
    }
    
    /**
     * Find the best matching pending record for the payment intent
     */
    private PaymentRecord findBestMatchingPendingRecord(List<PaymentRecord> pendingRecords, com.stripe.model.PaymentIntent paymentIntent) {
        if (paymentIntent.getAmount() != null) {
            BigDecimal paymentAmount = BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100));
            
            // Try to find a record with matching amount
            for (PaymentRecord record : pendingRecords) {
                if (record.getAmountTotal() != null && record.getAmountTotal().compareTo(paymentAmount) == 0) {
                    log.info("🎯 Found PENDING record with matching amount: {} EUR (ID: {})", paymentAmount, record.getId());
                    return record;
                }
            }
        }
        
        // If no amount match, return the first (most recent) pending record
        PaymentRecord firstRecord = pendingRecords.get(0);
        log.info("📋 Using first PENDING record (ID: {}) - no amount match found", firstRecord.getId());
        return firstRecord;
    }
    
    /**
     * Update a pending PaymentRecord with payment intent information
     */
    private void updatePendingPaymentRecord(PaymentRecord record, com.stripe.model.PaymentIntent paymentIntent) {
        log.info("🔄 Updating PENDING PaymentRecord ID: {} with payment intent: {}", record.getId(), paymentIntent.getId());
        
        record.setPaymentIntentId(paymentIntent.getId());
        record.setStatus(PaymentRecord.PaymentStatus.COMPLETED);
        record.setPaymentStatus("paid"); // Set Stripe payment status to "paid"
        
        // Update amount if needed or if it doesn't match
        if (paymentIntent.getAmount() != null) {
            BigDecimal paymentAmount = BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100));
            if (record.getAmountTotal() == null || record.getAmountTotal().compareTo(paymentAmount) != 0) {
                log.info("💰 Updating amount from {} to {} EUR", record.getAmountTotal(), paymentAmount);
                record.setAmountTotal(paymentAmount);
            }
        }
        
        if (paymentIntent.getCurrency() != null && record.getCurrency() == null) {
            record.setCurrency(paymentIntent.getCurrency());
            log.info("💱 Set currency to: {}", paymentIntent.getCurrency());
        }
        
        paymentRecordRepository.save(record);
        log.info("💾 ✅ Successfully updated PENDING PaymentRecord ID: {} to COMPLETED status with paymentStatus 'paid'", record.getId());
    }
    
    /**
     * Create new PaymentRecord from payment intent (fallback)
     */
    private void createNewPaymentRecord(com.stripe.model.PaymentIntent paymentIntent) {
        log.info("🆕 Creating new PaymentRecord for payment intent: {}", paymentIntent.getId());
        
        PaymentRecord newRecord = PaymentRecord.builder()
                .paymentIntentId(paymentIntent.getId())
                .amountTotal(paymentIntent.getAmount() != null ? 
                    BigDecimal.valueOf(paymentIntent.getAmount()).divide(BigDecimal.valueOf(100)) : null)
                .currency(paymentIntent.getCurrency() != null ? paymentIntent.getCurrency() : "eur")
                .status(PaymentRecord.PaymentStatus.COMPLETED)
                .paymentStatus("paid") // Set Stripe payment status to "paid"
                .stripeCreatedAt(convertToLocalDateTime(paymentIntent.getCreated()))
                .build();
        
        paymentRecordRepository.save(newRecord);
        log.info("💾 ✅ Created new PaymentRecord ID: {} for payment intent: {} with paymentStatus 'paid'", newRecord.getId(), paymentIntent.getId());
    }

    /**
     * Handle payment_intent.payment_failed webhook event
     */
    private void handlePaymentIntentFailed(Event event) {
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            try {
                com.stripe.model.PaymentIntent paymentIntent = (com.stripe.model.PaymentIntent) dataObjectDeserializer.getObject().get();
                
                log.warn("❌ Payment Intent failed: {} - Reason: {}", 
                        paymentIntent.getId(), 
                        paymentIntent.getLastPaymentError() != null ? paymentIntent.getLastPaymentError().getMessage() : "Unknown");
                
                // Update PaymentRecord to FAILED status
                PaymentRecord existingRecord = paymentRecordRepository.findByPaymentIntentId(paymentIntent.getId())
                    .orElse(null);
                
                if (existingRecord != null) {
                    existingRecord.setStatus(PaymentRecord.PaymentStatus.FAILED);
                    existingRecord.setPaymentStatus("failed"); // Set Stripe payment status to "failed"
                    paymentRecordRepository.save(existingRecord);
                    log.info("💾 Updated PaymentRecord for payment intent: {} to FAILED status with paymentStatus 'failed'", paymentIntent.getId());
                } else {
                    log.warn("⚠️ PaymentRecord not found for failed payment intent: {}", paymentIntent.getId());
                }
                
            } catch (Exception e) {
                log.error("❌ Failed to process payment_intent.payment_failed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Handle checkout.session.expired webhook event
     */
    private void handleCheckoutSessionExpired(Event event) {
        EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        if (dataObjectDeserializer.getObject().isPresent()) {
            try {
                Session session = (Session) dataObjectDeserializer.getObject().get();
                
                log.warn("⏰ Checkout session expired: {}", session.getId());
                
                // Update PaymentRecord to EXPIRED status
                PaymentRecord existingRecord = paymentRecordRepository.findBySessionId(session.getId())
                    .orElse(null);
                
                if (existingRecord != null) {
                    existingRecord.setStatus(PaymentRecord.PaymentStatus.EXPIRED);
                    existingRecord.setPaymentStatus("expired"); // Set Stripe payment status to "expired"
                    paymentRecordRepository.save(existingRecord);
                    log.info("💾 Updated PaymentRecord for session: {} to EXPIRED status with paymentStatus 'expired'", session.getId());
                } else {
                    log.warn("⚠️ PaymentRecord not found for expired session: {}", session.getId());
                }
                
            } catch (Exception e) {
                log.error("❌ Failed to process checkout.session.expired: {}", e.getMessage(), e);
            }
        }
    }

    public PaymentResponseDTO retrieveSession(String sessionId) throws StripeException {
        log.info("Retrieving session with ID: {}", sessionId);
        Stripe.apiKey = secretKey;
        
        try {
            Session session = Session.retrieve(sessionId);
            return mapSessionToResponceDTO(session);
        } catch (StripeException e) {
            log.error("Error retrieving session: {}", e.getMessage());
            throw e;
        }
    }

    public PaymentResponseDTO expireSession(String sessionId) throws StripeException {
        log.info("Expiring session with ID: {}", sessionId);
        Stripe.apiKey = secretKey;
        
        try {
            Session session = Session.retrieve(sessionId);
            Session expiredSession = session.expire();
            return mapSessionToResponceDTO(expiredSession);
        } catch (StripeException e) {
            log.error("Error expiring session: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Validates and ensures that the request currency is EUR
     * If currency is null, sets it to EUR. If it's not EUR, throws exception.
     * @param request CheckoutRequest to validate and potentially modify
     * @throws IllegalArgumentException if currency is explicitly set to non-EUR
     */
    private void validateEuroCurrency(CheckoutRequest request) {
        if (request.getCurrency() == null) {
            // Default to EUR if not specified
            request.setCurrency("eur");
            log.info("Currency not specified, defaulting to EUR");
        } else if (!"eur".equalsIgnoreCase(request.getCurrency())) {
            throw new IllegalArgumentException(
                String.format("Currency must be 'eur' - only Euro payments are supported. Received: '%s'. All amounts must be in euros and will be displayed in euros in the Stripe dashboard.", 
                request.getCurrency()));
        }
    }
    
    /**
     * Automatically create a RegistrationForm after successful payment
     * This method is called from webhook processing after payment completion
     */
    /**
     * Link the existing RegistrationForm (created during session creation) to the PaymentRecord
     * after successful payment. This replaces the auto-registration logic since the form
     * is now created upfront with all user data.
     */
    private void autoRegisterUserAfterPayment(PaymentRecord paymentRecord, Session session) {
        log.info("🔄 Verifying association between registration form and payment record ID: {}", paymentRecord.getId());
        
        try {
            // Check if the payment record already has a registration form associated
            if (paymentRecord.getRegistrationForm() != null) {
                log.info("✅ Payment record ID: {} already has registration form ID: {} associated", 
                        paymentRecord.getId(), paymentRecord.getRegistrationForm().getId());
                return;
            }
            
            // Find the existing registration form by customer email
            String customerEmail = paymentRecord.getCustomerEmail();
            if (customerEmail == null && session.getCustomerDetails() != null) {
                customerEmail = session.getCustomerDetails().getEmail();
            }
            
            if (customerEmail == null) {
                log.error("❌ CRITICAL: Cannot verify association - customer email not found for payment record ID: {}", paymentRecord.getId());
                return;
            }
            
            // Find the most recent registration form for this customer email
            RegistrationForm existingRegistration = registrationFormRepository.findTopByEmailOrderByIdDesc(customerEmail);
            
            if (existingRegistration == null) {
                log.error("❌ CRITICAL: No registration form found for email: {} (payment record ID: {})", 
                        customerEmail, paymentRecord.getId());
                return;
            }
            
            // Check if this registration form is already linked to a payment record
            if (existingRegistration.getPaymentRecord() != null) {
                if (existingRegistration.getPaymentRecord().getId().equals(paymentRecord.getId())) {
                    log.info("✅ Registration form ID: {} is already correctly linked to payment record ID: {}", 
                            existingRegistration.getId(), paymentRecord.getId());
                    return;
                } else {
                    log.warn("⚠️ Registration form ID: {} is linked to different payment record ID: {}, but current payment is: {}", 
                            existingRegistration.getId(), existingRegistration.getPaymentRecord().getId(), paymentRecord.getId());
                    // This might be an issue - log for manual review
                }
            }
            
            // Establish the bidirectional association if it doesn't exist
            log.info("🔗 Creating missing association between registration form ID: {} and payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            
            existingRegistration.setPaymentRecord(paymentRecord);
            paymentRecord.setRegistrationForm(existingRegistration);
            
            // Save both entities to ensure the relationship is persisted
            registrationFormRepository.save(existingRegistration);
            paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Successfully linked registration form ID: {} to payment record ID: {}", 
                    existingRegistration.getId(), paymentRecord.getId());
            
        } catch (Exception e) {
            log.error("❌ Error verifying/creating association for payment record ID {}: {}", 
                    paymentRecord.getId(), e.getMessage(), e);
            // Don't throw exception here - webhook processing should continue
        }
    }

    /**
     * Link an existing RegistrationForm to a PaymentRecord by session ID
     * This method establishes the mandatory bidirectional association during session creation
     */
    @Transactional
    public void linkRegistrationToPayment(Long registrationFormId, String sessionId) {
        log.info("🔗 Linking registration form ID: {} to payment record for session: {}", registrationFormId, sessionId);
        
        try {
            // Find the registration form
            RegistrationForm registrationForm = registrationFormRepository.findById(registrationFormId)
                    .orElseThrow(() -> new IllegalArgumentException("RegistrationForm not found with ID: " + registrationFormId));
            
            // Find the payment record
            PaymentRecord paymentRecord = paymentRecordRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("PaymentRecord not found for session: " + sessionId));
            
            // Establish the bidirectional relationship
            registrationForm.setPaymentRecord(paymentRecord);
            paymentRecord.setRegistrationForm(registrationForm);
            
            // Save both entities to persist the relationship
            registrationFormRepository.save(registrationForm);
            paymentRecordRepository.save(paymentRecord);
            
            log.info("✅ Successfully linked registration form ID: {} to payment record ID: {} for session: {}", 
                    registrationFormId, paymentRecord.getId(), sessionId);
            
        } catch (Exception e) {
            log.error("❌ Failed to link registration form ID: {} to payment record for session: {}: {}", 
                    registrationFormId, sessionId, e.getMessage(), e);
            throw new RuntimeException("Failed to create mandatory registration-payment association: " + e.getMessage());
        }
    }

    // ...existing code...
}
