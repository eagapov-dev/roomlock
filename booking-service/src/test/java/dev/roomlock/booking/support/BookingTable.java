package dev.roomlock.booking.support;

import dev.roomlock.booking.domain.CancelReason;
import dev.roomlock.booking.support.TestData.BookingDraft;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

/** Plain-SQL access to the booking table: constraint checks bypass JPA on purpose. */
@TestComponent
public class BookingTable {

  private static final String INSERT =
      """
      INSERT INTO booking (id, room_id, guest_id, check_in, check_out, status, cancel_reason,
                           hold_expires_at, payment_started_at, total_price, idempotency_key,
                           request_hash, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final JdbcTemplate jdbc;

  public BookingTable(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the row as is and returns its id. */
  public UUID insert(BookingDraft booking) {
    jdbc.update(
        INSERT,
        booking.id(),
        booking.roomId(),
        booking.guestId(),
        booking.checkIn(),
        booking.checkOut(),
        booking.status().name(),
        reasonName(booking.cancelReason()),
        utc(booking.holdExpiresAt()),
        utc(booking.paymentStartedAt()),
        booking.totalPrice(),
        booking.idempotencyKey(),
        booking.requestHash(),
        utc(booking.createdAt()),
        utc(booking.createdAt()));
    return booking.id();
  }

  /**
   * The guest the row belongs to. Pay and cancel act for a guest since the guest became the token
   * subject, so a test that wrote a row straight to the table asks it who owns what it wrote.
   */
  public UUID guestId(UUID bookingId) {
    return jdbc.queryForObject("SELECT guest_id FROM booking WHERE id = ?", UUID.class, bookingId);
  }

  /** Raw value of the status column. */
  public String status(UUID bookingId) {
    return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
  }

  /** Raw value of the cancel_reason column, null for a booking that is not cancelled. */
  public @Nullable String cancelReason(UUID bookingId) {
    return jdbc.queryForObject(
        "SELECT cancel_reason FROM booking WHERE id = ?", String.class, bookingId);
  }

  /** When the charge of the booking was started, empty for a booking that never entered PAYING. */
  public Optional<Instant> paymentStartedAt(UUID bookingId) {
    return Optional.ofNullable(
            jdbc.queryForObject(
                "SELECT payment_started_at FROM booking WHERE id = ?",
                OffsetDateTime.class,
                bookingId))
        .map(OffsetDateTime::toInstant);
  }

  /** Sets payment_started_at alone, to age a PAYING row the reconciliation sweep should pick up. */
  public void setPaymentStartedAt(UUID bookingId, Instant startedAt) {
    jdbc.update(
        "UPDATE booking SET payment_started_at = ? WHERE id = ?", utc(startedAt), bookingId);
  }

  /** How many rows the room has, whatever their status. */
  public int countForRoom(UUID roomId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM booking WHERE room_id = ?", Integer.class, roomId);
    return count == null ? 0 : count;
  }

  /** The generated stay column as PostgreSQL prints it, e.g. {@code [2026-11-10,2026-11-13)}. */
  public String stay(UUID bookingId) {
    return jdbc.queryForObject(
        "SELECT stay::text FROM booking WHERE id = ?", String.class, bookingId);
  }

  private static @Nullable String reasonName(@Nullable CancelReason reason) {
    return reason == null ? null : reason.name();
  }

  private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
