package dev.roomlock.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The trigger with scheduling on, as every profile but {@code test} runs it: the interval of
 * application.yml has to be a delay {@code @Scheduled} accepts, or the context does not start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentReconcileScheduleTest {

  @Autowired private ScheduledTaskHolder tasks;
  @MockitoBean private PaymentReconciler reconciler;

  @Test
  void shouldScheduleSweepWithIntervalOfConfiguration() {
    // given
    // when
    ScheduledTask scheduled = tasks.getScheduledTasks().iterator().next();

    // then
    assertThat(tasks.getScheduledTasks()).hasSize(1);
    Task task = scheduled.getTask();
    assertThat(task).isInstanceOf(FixedDelayTask.class);
    assertThat(((FixedDelayTask) task).getIntervalDuration()).isEqualTo(Duration.ofSeconds(10));
  }

  /** Only the trigger and scheduling: no database, no web layer, no auto-configuration. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @Import(PaymentReconcileScheduler.class)
  static class SchedulingOn {}
}
