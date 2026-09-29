package dev.roomlock.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A room is held for a guest until {@code holdExpiresAt}; the stay is half-open, so {@code
 * checkOut} is not one of its nights.
 */
public record BookingHeld(
    UUID eventId,
    UUID bookingId,
    UUID roomId,
    UUID guestId,
    LocalDate checkIn,
    LocalDate checkOut,
    BigDecimal totalPrice,
    String currency,
    Instant holdExpiresAt,
    Instant occurredAt)
    implements BookingEvent {}
