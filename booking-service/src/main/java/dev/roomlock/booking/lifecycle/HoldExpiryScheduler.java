package dev.roomlock.booking.lifecycle;

import dev.roomlock.booking.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the hold expiry sweep. Off under the {@code test} profile (rule SP-9): tests move the
 * clock and call the use case instead of waiting for a trigger (rule T-7).
 */
@Component
@Profile("!" + Profiles.TEST)
class HoldExpiryScheduler {

  private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

  private final LifecycleService lifecycle;

  HoldExpiryScheduler(LifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  /** One sweep per interval, counted from the end of the previous one. */
  @Scheduled(fixedDelayString = "${roomlock.hold.expiry.interval}")
  void expireHolds() {
    int cancelled = lifecycle.expireHolds();
    if (cancelled > 0) {
      log.info("Hold expiry cancelled {} booking(s)", cancelled);
    }
  }
}
