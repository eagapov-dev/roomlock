package dev.roomlock.booking.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the outbox is drained.
 *
 * @param interval time between two sweeps of the publisher
 * @param batchSize events one sweep publishes at most
 * @param stuckAfter refusals after which an event is reported as stuck rather than as an outage
 * @param lease how long one instance holds the right to publish; it renews the lease on every
 *     sweep, so this only has to outlast a sweep, and a dead instance costs one lease period
 * @param retention published events older than this are deleted
 */
@ConfigurationProperties("roomlock.outbox")
public record OutboxProperties(
    Duration interval, int batchSize, int stuckAfter, Duration lease, Retention retention) {

  /**
   * Pruning of published events.
   *
   * @param interval time between two prunes; nothing depends on it being frequent
   * @param olderThan age a published event has to reach before it is deleted, long enough that a
   *     human debugging yesterday's incident still finds the row
   * @param batchSize rows one prune deletes at most, so a first prune of a large table does not
   *     lock one statement for minutes
   */
  public record Retention(Duration interval, Duration olderThan, int batchSize) {}
}
