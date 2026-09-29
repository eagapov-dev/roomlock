package dev.roomlock.booking.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the outbox is drained.
 *
 * @param interval time between two sweeps of the publisher
 * @param batchSize events one sweep publishes at most
 * @param stuckAfter refusals after which an event is reported as stuck rather than as an outage
 */
@ConfigurationProperties("roomlock.outbox")
public record OutboxProperties(Duration interval, int batchSize, int stuckAfter) {}
