package dev.roomlock.booking.payment;

/**
 * What a {@link PaymentGateway} answered about a charge (rule J-2).
 *
 * <p>A decline is an answer, not a failure: the provider decided, and the booking is cancelled
 * because of it. {@link Unavailable} is the opposite - nothing was decided, so the booking keeps
 * waiting and the sweep asks again.
 */
public sealed interface ChargeOutcome {

  /** The money was taken. */
  record Approved(String paymentId) implements ChargeOutcome {}

  /** The provider refused the charge, for the reason it gave. */
  record Declined(String reason) implements ChargeOutcome {}

  /**
   * No answer came back: the provider was unreachable, too slow for the deadline, or refused the
   * call itself. The charge may or may not have happened, which is precisely why asking again is
   * safe - the booking id is the idempotency key.
   *
   * @param detail what went wrong, for the log; never shown to a client (rule E-2)
   */
  record Unavailable(String detail) implements ChargeOutcome {}
}
