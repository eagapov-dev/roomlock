package dev.roomlock.booking.api;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body of {@code POST /api/v1/bookings}. The idempotency key is not part of it: it travels
 * in the {@code Idempotency-Key} header (rule I-1). Neither is the guest: it is the subject of the
 * access token ({@link Guests}), so a caller can no longer name somebody else.
 *
 * <p>A field this record does not declare is rejected rather than ignored, which is what {@code
 * spring.jackson.deserialization.fail-on-unknown-properties} is turned back on for. That choice
 * exists for one field in particular: a client that still sends {@code guestId} is told 400 with
 * the field named, instead of being silently answered 201 for a booking that belongs to somebody
 * else than it asked for. Leniency is the wrong default when the field that went away was the
 * authorization hole.
 */
@StayDates
record HoldBookingRequest(
    @NotNull UUID roomId, @NotNull LocalDate checkIn, @NotNull LocalDate checkOut) {}
