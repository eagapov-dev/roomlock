package dev.roomlock.booking.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/**
 * Plain-SQL access to the outbox table: a published row cannot be written through the entity (rule
 * DB-5), and a test that needs one starts from here.
 */
@TestComponent
public class OutboxTable {

  private static final String INSERT =
      """
      INSERT INTO outbox_event (id, aggregate_id, type, payload, created_at, published_at, attempts)
      VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
      """;

  private final JdbcTemplate jdbc;

  public OutboxTable(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts an event nobody has published and returns its id. */
  public UUID insertPending(UUID aggregateId, String type, String payload, Instant createdAt) {
    return insert(aggregateId, type, payload, createdAt, null, 0);
  }

  /** Inserts an event that has already gone out and returns its id. */
  public UUID insertPublished(
      UUID aggregateId, String type, String payload, Instant createdAt, Instant publishedAt) {
    return insert(aggregateId, type, payload, createdAt, publishedAt, 0);
  }

  public Optional<Instant> publishedAt(UUID eventId) {
    return Optional.ofNullable(
            jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE id = ?",
                OffsetDateTime.class,
                eventId))
        .map(OffsetDateTime::toInstant);
  }

  public int attempts(UUID eventId) {
    Integer attempts =
        jdbc.queryForObject(
            "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, eventId);
    return attempts == null ? 0 : attempts;
  }

  /** Types of every row of the aggregate, oldest first. */
  public List<String> typesOf(UUID aggregateId) {
    return jdbc.queryForList(
        "SELECT type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at",
        String.class,
        aggregateId);
  }

  /** The payload of the only row of the aggregate. */
  public String payloadOf(UUID aggregateId) {
    return jdbc.queryForObject(
        "SELECT payload::text FROM outbox_event WHERE aggregate_id = ?", String.class, aggregateId);
  }

  /** The payload of the newest row of that type, for an aggregate that has several rows. */
  public String payloadOfLast(UUID aggregateId, String type) {
    return jdbc.queryForObject(
        """
        SELECT payload::text FROM outbox_event
        WHERE aggregate_id = ? AND type = ?
        ORDER BY created_at DESC
        LIMIT 1
        """,
        String.class,
        aggregateId,
        type);
  }

  public int count() {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
    return count == null ? 0 : count;
  }

  private UUID insert(
      UUID aggregateId,
      String type,
      String payload,
      Instant createdAt,
      @Nullable Instant publishedAt,
      int attempts) {
    UUID id = UUID.randomUUID();
    jdbc.update(INSERT, id, aggregateId, type, payload, utc(createdAt), utc(publishedAt), attempts);
    return id;
  }

  private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
