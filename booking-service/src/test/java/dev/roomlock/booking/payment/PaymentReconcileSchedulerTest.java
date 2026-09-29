package dev.roomlock.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import dev.roomlock.booking.support.BookingIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The reconciliation trigger: configured by {@code roomlock.payment}, off under the test profile.
 */
@BookingIntegrationTest
class PaymentReconcileSchedulerTest {

  @Autowired private ApplicationContext context;
  @Autowired private PaymentProperties properties;

  @Test
  void shouldNotRegisterSchedulerWhenTestProfileIsActive() {
    // given
    // when
    // then
    assertThat(context.getBeansOfType(PaymentReconcileScheduler.class)).isEmpty();
  }

  @Test
  void shouldReadDeadlineIntervalAgeAndBatchSizeFromConfiguration() {
    // given
    // when
    // then
    assertThat(properties.deadline()).isEqualTo(Duration.ofSeconds(3));
    assertThat(properties.reconcile().interval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.reconcile().after()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.reconcile().batchSize()).isEqualTo(100);
  }

  /** The age has to be past the deadline, or a sweep races a request that is still waiting. */
  @Test
  void shouldKeepTheStaleAgeLongerThanTheChargeDeadline() {
    // given
    // when
    // then
    assertThat(properties.reconcile().after()).isGreaterThan(properties.deadline());
  }

  @Test
  void shouldSweepBookingsLeftPayingWhenTriggered() {
    // given
    PaymentReconciler reconciler = mock(PaymentReconciler.class);
    given(reconciler.reconcileBatch()).willReturn(2);

    // when
    new PaymentReconcileScheduler(reconciler).reconcile();

    // then
    then(reconciler).should().reconcileBatch();
  }
}
