package dev.roomlock.payment.payments;

import dev.roomlock.payment.domain.Payment;

/**
 * Business outcomes of a charge (rule J-2). A declined charge is not one of them: it is a stored
 * {@link Payment} with {@code DECLINED} status, so the caller is told the decision instead of an
 * error.
 */
public sealed interface ChargeResult {

  /** The payment the outcome is about, decided now or on an earlier call. */
  Payment payment();

  /** This call decided the outcome and stored it. */
  record Charged(Payment payment) implements ChargeResult {}

  /** The booking was already charged; the stored payment is the answer again (rule I-1). */
  record AlreadyCharged(Payment payment) implements ChargeResult {}

  /**
   * The same booking id was charged with a different request, so this one is refused (rule I-2).
   */
  record Mismatched(Payment payment, ChargeMismatch mismatch) implements ChargeResult {}
}
