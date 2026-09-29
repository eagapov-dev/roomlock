package dev.roomlock.booking.payment;

import java.util.UUID;

/**
 * What the payment provider is asked to take from a guest.
 *
 * <p>The booking id is the idempotency key of the contract: the same booking is never charged
 * twice, and a second order for it is answered with what the first one decided (rule I-1). That is
 * what lets both a retried {@code POST /pay} and the reconciliation sweep simply ask again.
 *
 * @param amountMinor the total price of the booking in minor units, as the contract sends it
 * @param currency ISO 4217 code of {@code amountMinor}
 */
public record ChargeOrder(UUID bookingId, UUID guestId, long amountMinor, String currency) {}
