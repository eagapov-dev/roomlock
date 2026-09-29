package dev.roomlock.booking.outbox;

import dev.roomlock.booking.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the outbox sweep. Off under the {@code test} profile (rule SP-9): tests publish by
 * calling the use case, so nothing races with their assertions (rule T-7).
 */
@Component
@Profile("!" + Profiles.TEST)
class OutboxScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

  private final OutboxPublisher publisher;

  OutboxScheduler(OutboxPublisher publisher) {
    this.publisher = publisher;
  }

  /** One sweep per interval, counted from the end of the previous one. */
  @Scheduled(fixedDelayString = "${roomlock.outbox.interval}")
  void publish() {
    int published = publisher.publishBatch();
    if (published > 0) {
      log.info("Outbox published {} event(s)", published);
    }
  }
}
