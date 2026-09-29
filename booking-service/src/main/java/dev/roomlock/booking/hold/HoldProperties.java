package dev.roomlock.booking.hold;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a new booking stays HELD before it may expire.
 *
 * @param ttl time from the hold to {@code hold_expires_at}
 */
@ConfigurationProperties("roomlock.hold")
public record HoldProperties(Duration ttl) {}
