package dev.roomlock.payment.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

/** Plain-SQL access to the payment table: assertions read the row the service wrote. */
@TestComponent
public class PaymentTable {

  private static final String COLUMNS =
      """
      SELECT id, booking_id, guest_id, amount_minor, currency, status, decline_reason,
             charged_at, refunded_at
      FROM payment
      """;

  private static final RowMapper<Row> ROW_MAPPER =
      (rs, rowNumber) ->
          new Row(
              rs.getObject("id", UUID.class),
              rs.getObject("booking_id", UUID.class),
              rs.getObject("guest_id", UUID.class),
              rs.getLong("amount_minor"),
              rs.getString("currency"),
              rs.getString("status"),
              rs.getString("decline_reason"),
              instant(rs.getObject("charged_at", OffsetDateTime.class)),
              instant(rs.getObject("refunded_at", OffsetDateTime.class)));

  private final JdbcTemplate jdbc;

  public PaymentTable(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The payment of the booking, empty while the booking has not been charged. */
  public Optional<Row> findByBookingId(UUID bookingId) {
    List<Row> rows = jdbc.query(COLUMNS + " WHERE booking_id = ?", ROW_MAPPER, bookingId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  /** How many rows the booking has; more than one would mean it was charged twice. */
  public int countForBooking(UUID bookingId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM payment WHERE booking_id = ?", Integer.class, bookingId);
    return count == null ? 0 : count;
  }

  /** One recorded payment; {@code refundedAt} is null while the money has not gone back. */
  public record Row(
      UUID id,
      UUID bookingId,
      UUID guestId,
      long amountMinor,
      String currency,
      String status,
      @Nullable String declineReason,
      Instant chargedAt,
      @Nullable Instant refundedAt) {}

  private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
