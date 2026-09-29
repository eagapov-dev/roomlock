package dev.roomlock.payment.payments;

import org.springframework.stereotype.Component;

/** The stubbed acquirer: it decides a charge without talking to anybody. */
@Component
public class ChargeRule {

  public ChargeRule(PaymentProperties properties) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** The decision for the charge; the same command always decides the same way. */
  public ChargeDecision decide(ChargeCommand command) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
