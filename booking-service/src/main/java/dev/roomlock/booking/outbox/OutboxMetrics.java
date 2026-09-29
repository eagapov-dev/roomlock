package dev.roomlock.booking.outbox;

import dev.roomlock.booking.infra.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** The outbox as Grafana sees it: what went out, what could not, and how much is still waiting. */
@Component
public class OutboxMetrics {

  /** Events the publisher handed to the broker. */
  static final String PUBLISHED = "roomlock.outbox.published";

  /** Sweeps that stopped because the broker refused an event. */
  static final String FAILURES = "roomlock.outbox.failures";

  /** Events still waiting, the backlog a dashboard watches instead of guessing the lag. */
  static final String UNPUBLISHED = "roomlock.outbox.unpublished";

  private final Counter published;
  private final Counter failures;

  public OutboxMetrics(MeterRegistry registry, OutboxEventRepository events) {
    this.published =
        Counter.builder(PUBLISHED)
            .description("Events published from the outbox")
            .register(registry);
    this.failures =
        Counter.builder(FAILURES).description("Events the broker refused").register(registry);
    Gauge.builder(UNPUBLISHED, events, OutboxEventRepository::countByPublishedAtIsNull)
        .description("Events the outbox still holds")
        .register(registry);
  }

  void published(int count) {
    published.increment(count);
  }

  void failed() {
    failures.increment();
  }
}
