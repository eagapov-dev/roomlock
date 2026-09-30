package dev.roomlock.payment.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the stubbed acquirer, all of them in {@code application.yml}.
 *
 * <p>Package-private, like the rule it configures: {@code @ConfigurationPropertiesScan} finds it by
 * scanning and binds it reflectively, so nothing outside this package has to see it.
 *
 * @param decision the rule {@link ChargeRule} applies to every charge
 */
@ConfigurationProperties("roomlock.payment")
record PaymentProperties(Decision decision) {

  /**
   * @param mode which rule decides
   * @param declineAboveMinor in {@link DecisionMode#THRESHOLD}, the largest amount in minor units
   *     that is still approved
   */
  record Decision(DecisionMode mode, long declineAboveMinor) {}
}
