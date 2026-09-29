package dev.roomlock.contract.v1;

import java.time.Instant;
import java.util.UUID;

/** The booking no longer holds its room, for the reason it carries. */
public record BookingCancelled(
    UUID eventId,
    UUID bookingId,
    UUID roomId,
    UUID guestId,
    CancellationReason reason,
    Instant occurredAt)
    implements BookingEvent {}
