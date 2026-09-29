package dev.roomlock.payment.payments;

import dev.roomlock.payment.domain.Payment;
import java.util.UUID;

/**
 * Business outcomes of a refund (rule J-2). A booking nobody ever paid for is one of them rather
 * than an error: both callers of the use case - the gRPC client and the refund consumer - need a
 * final answer, not something to retry forever.
 */
public sealed interface RefundResult {

  /** This call gave the money back. */
  record Refunded(Payment payment) implements RefundResult {}

  /** The booking was already refunded; the answer is the same as the first time (rule I-1). */
  record AlreadyRefunded(Payment payment) implements RefundResult {}

  /** The charge of the booking was declined, so there is no money to give back. */
  record NotApproved(Payment payment) implements RefundResult {}

  /** No payment was ever recorded for the booking. */
  record NotCharged(UUID bookingId) implements RefundResult {}
}
