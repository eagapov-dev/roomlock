package dev.roomlock.booking.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which locking strategy holds a room and how hard it tries.
 *
 * @param strategy the strategy every hold goes through
 * @param maxAttempts attempts of a strategy that retries a lost race (OPTIMISTIC); 1 disables
 *     retrying
 */
@ConfigurationProperties("roomlock.locking")
public record LockingProperties(LockingStrategy strategy, int maxAttempts) {}
