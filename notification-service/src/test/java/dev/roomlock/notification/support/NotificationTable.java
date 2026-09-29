package dev.roomlock.notification.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

/** Plain-SQL access to the notification table: assertions read the row the consumer wrote. */
@TestComponent
public class NotificationTable {

  private static final String COLUMNS =
      """
      SELECT event_id, event_type, booking_id, guest_id, kind, channel, subject, body,
             occurred_at, handled_at, sent_at
      FROM notification
      """;

  private static final RowMapper<Row> ROW_MAPPER =
      (rs, rowNumber) ->
          new Row(
              rs.getObject("event_id", UUID.class),
              rs.getString("event_type"),
              rs.getObject("booking_id", UUID.class),
              rs.getObject("guest_id", UUID.class),
              rs.getString("kind"),
              rs.getString("channel"),
              rs.getString("subject"),
              rs.getString("body"),
              instant(rs.getObject("occurred_at", OffsetDateTime.class)),
              instant(rs.getObject("handled_at", OffsetDateTime.class)),
              instant(rs.getObject("sent_at", OffsetDateTime.class)));

  private final JdbcTemplate jdbc;

  public NotificationTable(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The delivery recorded for the event, empty while the event has not been handled. */
  public Optional<Row> findByEventId(UUID eventId) {
    List<Row> rows = jdbc.query(COLUMNS + " WHERE event_id = ?", ROW_MAPPER, eventId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  /** Deliveries recorded for one booking, oldest event first. */
  public List<Row> findByBookingId(UUID bookingId) {
    return jdbc.query(
        COLUMNS + " WHERE booking_id = ? ORDER BY occurred_at, kind", ROW_MAPPER, bookingId);
  }

  /** How many rows the event has; more than one would mean a duplicate was delivered. */
  public int countForEvent(UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification WHERE event_id = ?", Integer.class, eventId);
    return count == null ? 0 : count;
  }

  private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  /** One recorded delivery; {@code sentAt} is null while the sender has not confirmed it. */
  public record Row(
      UUID eventId,
      String eventType,
      UUID bookingId,
      UUID guestId,
      String kind,
      String channel,
      String subject,
      String body,
      Instant occurredAt,
      Instant handledAt,
      @Nullable Instant sentAt) {}
}
