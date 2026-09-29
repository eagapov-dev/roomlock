package dev.roomlock.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
class OutboxScheduleTest {

  @Autowired private ScheduledTaskHolder tasks;
  @MockitoBean private OutboxPublisher publisher;

  @Test
  void shouldScheduleBothSweepsWithTheIntervalsOfConfiguration() {
    // given
    // when
    Map<String, Duration> intervals = intervalsByMethod();

    // then publishing is a hot path and pruning is housekeeping, so they do not share a trigger
    assertThat(intervals)
        .containsEntry("publish", Duration.ofSeconds(1))
        .containsEntry("prune", Duration.ofHours(1))
        .hasSize(2);
  }

  /** The delay of each trigger, by the method it fires, as {@code @Scheduled} resolved it. */
  private Map<String, Duration> intervalsByMethod() {
    Map<String, Duration> intervals = new HashMap<>();
    for (ScheduledTask scheduled : tasks.getScheduledTasks()) {
      Task task = scheduled.getTask();
      assertThat(task).isInstanceOf(FixedDelayTask.class);
      String method = task.toString().substring(task.toString().lastIndexOf('.') + 1);
      intervals.put(method, ((FixedDelayTask) task).getIntervalDuration());
    }
    return intervals;
  }

  /** Only the trigger and scheduling: no database, no web layer, no auto-configuration. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @Import(OutboxScheduler.class)
  static class SchedulingOn {}
}
