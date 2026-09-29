package dev.roomlock.booking.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the outbox is drained.
 *
 * @param interval time between two sweeps of the publisher
 * @param batchSize events one sweep publishes at most
 */
@ConfigurationProperties("roomlock.outbox")
public record OutboxProperties(Duration interval, int batchSize) {}
