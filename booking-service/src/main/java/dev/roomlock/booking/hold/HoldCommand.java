package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.DateRange;
import java.util.UUID;

/**
 * A request to hold a room for a stay. The idempotency key is the client's retry token: the same
 * key of the same guest never holds twice (rule I-1).
 */
public record HoldCommand(UUID roomId, UUID guestId, DateRange stay, String idempotencyKey) {}
