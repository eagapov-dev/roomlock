package dev.roomlock.booking.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import dev.roomlock.booking.support.BookingIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** The expiry trigger: configured by {@code roomlock.hold.expiry}, off under the test profile. */
@BookingIntegrationTest
class HoldExpirySchedulerTest {

  @Autowired private ApplicationContext context;
  @Autowired private HoldExpiryProperties properties;

  @Test
  void shouldNotRegisterSchedulerWhenTestProfileIsActive() {
    // given
    // when
    // then
    assertThat(context.getBeansOfType(HoldExpiryScheduler.class)).isEmpty();
  }

  @Test
  void shouldScheduleNoTaskWhenTestProfileIsActive() {
    // given
    // when
    // then
    assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
  }

  @Test
  void shouldReadIntervalAndBatchSizeFromConfiguration() {
    // given
    // when
    // then
    assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.batchSize()).isEqualTo(500);
  }

  @Test
  void shouldSweepExpiredHoldsWhenTriggered() {
    // given
    LifecycleService lifecycle = mock(LifecycleService.class);
    given(lifecycle.expireHolds()).willReturn(2);

    // when
    new HoldExpiryScheduler(lifecycle).expireHolds();

    // then
    then(lifecycle).should().expireHolds();
  }
}
