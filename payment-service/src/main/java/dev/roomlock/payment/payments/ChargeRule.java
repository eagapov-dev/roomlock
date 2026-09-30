package dev.roomlock.payment.payments;

import org.springframework.stereotype.Component;

/**
 * The stubbed acquirer: it decides a charge without talking to anybody.
 *
 * <p>Deterministic on purpose - the decision is a pure function of the command and the configured
 * {@link DecisionMode}, with no randomness anywhere - so a demo or a load test can drive the
 * approval and the decline path deliberately: {@code roomlock.payment.decision.mode} answers the
 * same way to everything, and {@code THRESHOLD} answers by the amount alone.
 */
@Component
class ChargeRule {

  private final DecisionMode mode;
  private final long declineAboveMinor;

  ChargeRule(PaymentProperties properties) {
    this.mode = properties.decision().mode();
    this.declineAboveMinor = properties.decision().declineAboveMinor();
  }

  /** The decision for the charge; the same command always decides the same way. */
  ChargeDecision decide(ChargeCommand command) {
    return switch (mode) {
      case APPROVE_ALL -> ChargeDecision.approve();
      case DECLINE_ALL ->
          ChargeDecision.decline("Declined by configuration: roomlock.payment.decision.mode");
      case THRESHOLD -> byAmount(command.amountMinor(), command.currency());
    };
  }

  private ChargeDecision byAmount(long amountMinor, String currency) {
    if (amountMinor <= declineAboveMinor) {
      return ChargeDecision.approve();
    }
    return ChargeDecision.decline(
        "Amount %d %s is above the limit of %d"
            .formatted(amountMinor, currency, declineAboveMinor));
  }
}
