package dev.roomlock.payment.payments;

/** How the stubbed acquirer of {@link ChargeRule} decides, from {@code application.yml}. */
public enum DecisionMode {
  /** Every charge is approved, whatever it asks for. */
  APPROVE_ALL,

  /** Every charge is declined: the failure path of a demo or a load test, on purpose. */
  DECLINE_ALL,

  /** A charge above {@code roomlock.payment.decision.decline-above-minor} is declined. */
  THRESHOLD
}
