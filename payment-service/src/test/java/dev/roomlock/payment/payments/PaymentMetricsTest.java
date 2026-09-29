package dev.roomlock.payment.payments;

import static dev.roomlock.payment.support.TestData.AMOUNT_MINOR;
import static dev.roomlock.payment.support.TestData.CURRENCY;
import static dev.roomlock.payment.support.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.payment.domain.Payment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a charge and a refund report. The outcomes are handed in directly: what needs pinning is the
 * tag each one is counted under, since the dashboard queries exactly these.
 */
class PaymentMetricsTest {

  private static final String CHARGES = "roomlock.payment.charges";
  private static final String REFUNDS = "roomlock.payment.refunds";
  private static final String DURATION = "roomlock.payment.charge.duration";
  private static final String OUTCOME = "outcome";

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final PaymentMetrics metrics = new PaymentMetrics(registry);

  @Test
  void shouldCountApprovedAndDeclinedApartWhenChargeDecides() {
    // given
    ChargeResult approved = new ChargeResult.Charged(approvedPayment());
    ChargeResult declined = new ChargeResult.Charged(declinedPayment());

    // when
    metrics.charged(approved);
    metrics.charged(declined);

    // then
    assertThat(charges("approved")).isEqualTo(1);
    assertThat(charges("declined")).isEqualTo(1);
  }

  @Test
  void shouldCountAReplayApartFromADecisionWhenBookingWasCharged() {
    // given
    ChargeResult replay = new ChargeResult.AlreadyCharged(approvedPayment());

    // when
    metrics.charged(replay);

    // then
    assertThat(charges("replayed")).isEqualTo(1);
    assertThat(charges("approved")).isZero();
  }

  @Test
  void shouldCountARefusedChargeWhenTheRequestDoesNotMatch() {
    // given
    ChargeResult mismatched = new ChargeResult.Mismatched(approvedPayment(), ChargeMismatch.AMOUNT);

    // when
    metrics.charged(mismatched);

    // then
    assertThat(charges("mismatched")).isEqualTo(1);
  }

  @Test
  void shouldCountEveryRefundOutcomeUnderItsOwnTag() {
    // given
    Payment approved = approvedPayment();

    // when
    metrics.refunded(new RefundResult.Refunded(approved));
    metrics.refunded(new RefundResult.AlreadyRefunded(approved));
    metrics.refunded(new RefundResult.NotApproved(declinedPayment()));
    metrics.refunded(new RefundResult.NotCharged(UUID.randomUUID()));

    // then
    assertThat(refunds("refunded")).isEqualTo(1);
    assertThat(refunds("already_refunded")).isEqualTo(1);
    assertThat(refunds("not_approved")).isEqualTo(1);
    assertThat(refunds("not_charged")).isEqualTo(1);
  }

  @Test
  void shouldTimeTheChargeAndReturnItsOutcomeWhenItIsDecided() {
    // given
    ChargeResult charged = new ChargeResult.Charged(approvedPayment());

    // when
    ChargeResult returned = metrics.timed(() -> charged);

    // then
    assertThat(returned).isSameAs(charged);
    assertThat(registry.get(DURATION).timer().count()).isEqualTo(1);
  }

  @Test
  void shouldRegisterEveryOutcomeWhenMetricsAreCreated() {
    // given nothing has happened yet

    // when
    // then a panel has a series to draw from the first scrape
    assertThat(charges("approved")).isZero();
    assertThat(charges("declined")).isZero();
    assertThat(charges("replayed")).isZero();
    assertThat(charges("mismatched")).isZero();
    assertThat(refunds("refunded")).isZero();
    assertThat(refunds("already_refunded")).isZero();
    assertThat(refunds("not_approved")).isZero();
    assertThat(refunds("not_charged")).isZero();
  }

  private double charges(String outcome) {
    return registry.get(CHARGES).tag(OUTCOME, outcome).counter().count();
  }

  private double refunds(String outcome) {
    return registry.get(REFUNDS).tag(OUTCOME, outcome).counter().count();
  }

  private static Payment approvedPayment() {
    return Payment.approved(UUID.randomUUID(), UUID.randomUUID(), AMOUNT_MINOR, CURRENCY, NOW);
  }

  private static Payment declinedPayment() {
    return Payment.declined(
        UUID.randomUUID(), UUID.randomUUID(), AMOUNT_MINOR, CURRENCY, "over the limit", NOW);
  }
}
