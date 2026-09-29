package dev.roomlock.booking.payment;

/**
 * Where a charge goes. A port, so the payment flow can be driven without a provider and the gRPC
 * adapter can be tested without the flow (rule ST-6).
 *
 * <p>A refund has no port of its own: a cancelled booking asks for its money back through the
 * {@code RefundRequested} event of the contract, which payment-service consumes idempotently (rule
 * K-1).
 */
public interface PaymentGateway {

  /**
   * Asks the provider to take the money of the order and answers what it decided. Never throws for
   * a provider that is unreachable or slow: that is {@link ChargeOutcome.Unavailable}.
   *
   * <p>Must not be called inside a database transaction: the round trip would hold a connection of
   * a pool of ten for as long as the provider takes to answer.
   */
  ChargeOutcome charge(ChargeOrder order);
}
