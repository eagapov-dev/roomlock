package dev.roomlock.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A cancelled booking that had been paid for asks for its money back. The payment service answers
 * the same way to a repeated request, so a redelivery refunds once.
 */
public record RefundRequested(
    UUID eventId,
    UUID bookingId,
    UUID guestId,
    BigDecimal totalPrice,
    String currency,
    Instant occurredAt)
    implements BookingEvent {}
