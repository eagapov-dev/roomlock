package dev.roomlock.booking.api;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body of {@code POST /api/v1/bookings}. The idempotency key is not part of it: it travels
 * in the {@code Idempotency-Key} header (rule I-1).
 */
@StayDates
record HoldBookingRequest(
    @NotNull UUID roomId,
    @NotNull UUID guestId,
    @NotNull LocalDate checkIn,
    @NotNull LocalDate checkOut) {}
