package dev.roomlock.payment.payments;

import java.util.UUID;

/**
 * A request to charge a booking. The booking id is the idempotency key: the same booking is never
 * charged twice, and a second command for it is answered with what the first one decided (rule
 * I-1).
 *
 * @param amountMinor money in minor units, as the gRPC contract sends it
 * @param currency ISO 4217 code of {@code amountMinor}, for example {@code PLN}
 */
public record ChargeCommand(UUID bookingId, UUID guestId, long amountMinor, String currency) {}
