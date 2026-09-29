package dev.roomlock.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The stay is paid for and the room belongs to the guest. */
public record BookingConfirmed(
    UUID eventId,
    UUID bookingId,
    UUID roomId,
    UUID guestId,
    LocalDate checkIn,
    LocalDate checkOut,
    BigDecimal totalPrice,
    String currency,
    Instant occurredAt)
    implements BookingEvent {}
