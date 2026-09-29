package dev.roomlock.payment.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the stubbed acquirer, all of them in {@code application.yml}.
 *
 * @param decision the rule {@link ChargeRule} applies to every charge
 */
@ConfigurationProperties("roomlock.payment")
public record PaymentProperties(Decision decision) {

  /**
   * @param mode which rule decides
   * @param declineAboveMinor in {@link DecisionMode#THRESHOLD}, the largest amount in minor units
   *     that is still approved
   */
  public record Decision(DecisionMode mode, long declineAboveMinor) {}
}
