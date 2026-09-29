package dev.roomlock.booking.lifecycle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The sweep that cancels expired holds.
 *
 * @param interval delay between the end of one sweep and the start of the next one; written as an
 *     ISO-8601 duration because {@code @Scheduled} reads the same value
 * @param batchSize holds one sweep cancels at most, so a backlog never grows one transaction
 */
@ConfigurationProperties("roomlock.hold.expiry")
public record HoldExpiryProperties(Duration interval, int batchSize) {}
