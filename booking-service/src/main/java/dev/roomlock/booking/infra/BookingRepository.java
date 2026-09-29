package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.Booking;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

  /** The booking a guest created with this idempotency key, if the key was used (rule I-1). */
  Optional<Booking> findByGuestIdAndIdempotencyKey(UUID guestId, String idempotencyKey);

  /**
   * Whether a booking that still holds the room covers a night of the stay: the statuses and the
   * half-open overlap of {@code booking_no_overlap}, asked by the application (D2).
   */
  @Query(
      value =
          """
          SELECT EXISTS (
            SELECT 1 FROM booking
            WHERE room_id = :roomId
              AND status IN ('HELD', 'PAYING', 'CONFIRMED')
              AND stay && daterange(CAST(:checkIn AS date), CAST(:checkOut AS date), '[)')
          )
          """,
      nativeQuery = true)
  boolean existsActiveOverlap(
      @Param("roomId") UUID roomId,
      @Param("checkIn") LocalDate checkIn,
      @Param("checkOut") LocalDate checkOut);

  /**
   * Cancels up to {@code batchSize} holds whose window has ended, and answers the bookings it
   * cancelled, so the caller can record an event for each of them (rule DB-5). The CTE picks the
   * batch, driven by {@code booking_status_hold_expires_at_idx}, and locks it: {@code SKIP LOCKED}
   * passes by a row another transaction is writing instead of waiting for it, and the next sweep
   * picks that one up. A CTE rather than {@code id IN (SELECT ... LIMIT ...)} because PostgreSQL
   * may plan the subquery as a re-scanned semi-join, which cancels more rows than the batch. The
   * {@code status = 'HELD'} of the UPDATE is the transition itself: a booking a concurrent request
   * has confirmed or cancelled keeps what it has.
   */
  @Transactional
  @Query(
      value =
          """
          WITH expired AS (
            SELECT id FROM booking
            WHERE status = 'HELD'
              AND hold_expires_at <= CAST(:now AS timestamptz)
            ORDER BY hold_expires_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
          )
          UPDATE booking
          SET status = 'CANCELLED',
              cancel_reason = 'HOLD_EXPIRED',
              updated_at = CAST(:now AS timestamptz)
          FROM expired
          WHERE booking.id = expired.id AND booking.status = 'HELD'
          RETURNING booking.id
          """,
      nativeQuery = true)
  List<UUID> expireHolds(@Param("now") Instant now, @Param("batchSize") int batchSize);

  /**
   * Cancels the booking on behalf of its guest and answers whether it did (rule DB-5). Only a HELD
   * booking may go: a row a concurrent request has moved to PAYING, CONFIRMED or CANCELLED keeps
   * its status, and the caller reads it back to learn which outcome that is.
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          UPDATE booking
          SET status = 'CANCELLED',
              cancel_reason = 'USER_CANCELLED',
              updated_at = CAST(:now AS timestamptz)
          WHERE id = :id AND status = 'HELD'
          """,
      nativeQuery = true)
  int cancelHeld(@Param("id") UUID id, @Param("now") Instant now);
}
