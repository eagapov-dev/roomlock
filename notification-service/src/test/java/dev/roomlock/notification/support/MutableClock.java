package dev.roomlock.notification.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe clock that tests move forward explicitly instead of waiting (rule T-7). */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  public MutableClock(Instant start) {
    this(new AtomicReference<>(start), ZoneOffset.UTC);
  }

  private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  /** Moves the clock forward (or backward for a negative duration). */
  public void advance(Duration duration) {
    now.updateAndGet(current -> current.plus(duration));
  }

  /** Sets the clock to an exact instant. */
  public void setInstant(Instant instant) {
    now.set(instant);
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  /** Returns a view in another zone that shares the same current instant. */
  @Override
  public Clock withZone(ZoneId newZone) {
    return new MutableClock(now, newZone);
  }
}
