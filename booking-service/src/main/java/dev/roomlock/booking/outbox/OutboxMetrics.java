package dev.roomlock.booking.outbox;

import dev.roomlock.booking.infra.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** The outbox as Grafana sees it: what went out, what could not, and how much is still waiting. */
@Component
public class OutboxMetrics {

  public OutboxMetrics(MeterRegistry registry, OutboxEventRepository events) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  void published(int count) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  void failed() {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
