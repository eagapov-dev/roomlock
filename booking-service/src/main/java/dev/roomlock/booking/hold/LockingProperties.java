package dev.roomlock.booking.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which locking strategy holds a room, and the settings of the one strategy that has any.
 *
 * @param strategy the strategy every hold goes through
 * @param optimistic settings of {@link LockingStrategy#OPTIMISTIC} alone
 */
@ConfigurationProperties("roomlock.locking")
public record LockingProperties(LockingStrategy strategy, Optimistic optimistic) {

  /**
   * Under {@code roomlock.locking.optimistic}, and not one level up: retrying is what the
   * optimistic strategy does with a lost race, while PESSIMISTIC waits for the row lock and
   * CONSTRAINT lets the database answer, so neither has a second attempt to configure (rule DB-8).
   *
   * @param maxAttempts attempts of one hold; 1 disables retrying
   */
  public record Optimistic(int maxAttempts) {}
}
