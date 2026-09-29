package dev.roomlock.booking.infra;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The lease that makes one instance the publisher of the outbox.
 *
 * <p>Plain SQL rather than an entity: a single row with no identity worth mapping, and an upsert
 * whose conflict action is conditional - which is the whole mechanism - is not something JPA
 * expresses. The row is created by the first instance that asks, so nothing has to seed it.
 *
 * <p>The lease bounds the window in which two instances can both publish; it does not close it. An
 * instance that stalls long enough for its lease to expire may still be sending when the next one
 * takes over, and the duplicate that produces is a consumer's business - every event carries an id
 * for exactly that (rule K-3).
 */
@Component
public class PublisherLease {

  /** There is one outbox, so there is one lease. */
  private static final String LEASE = "publisher";

  private static final String ACQUIRE =
      """
      INSERT INTO outbox_lease (id, holder, acquired_at, expires_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE
      SET holder = excluded.holder,
          acquired_at = excluded.acquired_at,
          expires_at = excluded.expires_at
      WHERE outbox_lease.holder = excluded.holder OR outbox_lease.expires_at <= excluded.acquired_at
      """;

  private final JdbcTemplate jdbc;

  public PublisherLease(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Takes the lease, or renews it if this holder already has it.
   *
   * @return true if the caller may publish until {@code now + leaseFor}
   */
  public boolean heldBy(UUID holder, Instant now, Duration leaseFor) {
    return jdbc.update(ACQUIRE, LEASE, holder, utc(now), utc(now.plus(leaseFor))) == 1;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
