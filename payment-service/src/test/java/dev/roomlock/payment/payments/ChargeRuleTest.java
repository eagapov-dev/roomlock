package dev.roomlock.payment.payments;

import static dev.roomlock.payment.support.TestData.CURRENCY;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.payment.payments.PaymentProperties.Decision;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The stubbed acquirer decides by configuration alone, so the same charge always gets the same
 * answer and a demo can drive either path on purpose.
 */
class ChargeRuleTest {

  private static final long LIMIT_MINOR = 100_000L;

  @ParameterizedTest
  @ValueSource(longs = {1L, LIMIT_MINOR - 1, LIMIT_MINOR})
  void shouldApproveWhenAmountIsNotAboveTheLimit(long amountMinor) {
    // given
    ChargeRule rule = ruleOf(DecisionMode.THRESHOLD);

    // when
    ChargeDecision decision = rule.decide(chargeOf(amountMinor));

    // then
    assertThat(decision.approved()).isTrue();
    assertThat(decision.declineReason()).isNull();
  }

  @Test
  void shouldDeclineWithTheAmountAndTheLimitWhenAmountIsAboveTheLimit() {
    // given
    ChargeRule rule = ruleOf(DecisionMode.THRESHOLD);

    // when
    ChargeDecision decision = rule.decide(chargeOf(LIMIT_MINOR + 1));

    // then
    assertThat(decision.approved()).isFalse();
    assertThat(decision.requiredDeclineReason())
        .contains(String.valueOf(LIMIT_MINOR + 1))
        .contains(String.valueOf(LIMIT_MINOR))
        .contains(CURRENCY);
  }

  @Test
  void shouldApproveEveryAmountWhenModeIsApproveAll() {
    // given
    ChargeRule rule = ruleOf(DecisionMode.APPROVE_ALL);

    // when
    ChargeDecision decision = rule.decide(chargeOf(LIMIT_MINOR * 1000));

    // then
    assertThat(decision.approved()).isTrue();
  }

  @Test
  void shouldDeclineEveryAmountWhenModeIsDeclineAll() {
    // given
    ChargeRule rule = ruleOf(DecisionMode.DECLINE_ALL);

    // when
    ChargeDecision decision = rule.decide(chargeOf(1L));

    // then
    assertThat(decision.approved()).isFalse();
    assertThat(decision.requiredDeclineReason()).contains("roomlock.payment.decision.mode");
  }

  @Test
  void shouldDecideTheSameWayWhenTheSameChargeIsAskedTwice() {
    // given
    ChargeRule rule = ruleOf(DecisionMode.THRESHOLD);
    ChargeCommand command = chargeOf(LIMIT_MINOR + 1);

    // when
    ChargeDecision first = rule.decide(command);
    ChargeDecision second = rule.decide(command);

    // then
    assertThat(second).isEqualTo(first);
  }

  private static ChargeRule ruleOf(DecisionMode mode) {
    return new ChargeRule(new PaymentProperties(new Decision(mode, LIMIT_MINOR)));
  }

  private static ChargeCommand chargeOf(long amountMinor) {
    return new ChargeCommand(UUID.randomUUID(), UUID.randomUUID(), amountMinor, CURRENCY);
  }
}
