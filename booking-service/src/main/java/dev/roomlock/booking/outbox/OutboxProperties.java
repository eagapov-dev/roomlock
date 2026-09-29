package dev.roomlock.booking.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the outbox is drained.
 *
 * @param interval time between two sweeps of the publisher
 * @param batchSize events one sweep publishes at most
 * @param currency the currency every price of this deployment is in; the booking table has no
 *     currency column, so it belongs to the deployment rather than to a booking
 */
@ConfigurationProperties("roomlock.outbox")
public record OutboxProperties(Duration interval, int batchSize, String currency) {}
