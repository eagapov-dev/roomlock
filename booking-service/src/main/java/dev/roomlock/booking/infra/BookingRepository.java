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
   * The booking, if it belongs to this guest. Ownership is a condition of the query rather than a
   * check on the row it returned, so a caller that does not own the booking gets an empty {@link
   * Optional} - and with it a 404 - and learns nothing about whether the id exists. A 403 would
   * answer that question for anybody enumerating ids. {@code guest_id} is {@code updatable =
   * false}, so this can never go stale between the read and what follows it.
   */
  Optional<Booking> findByIdAndGuestId(UUID id, UUID guestId);

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

  /**
   * Cancels a booking whose stay was already paid for and answers whether it did (rule DB-5). Split
   * from {@link #cancelHeld} rather than widened to {@code status IN (...)} because the caller has
   * to know which of the two happened: only this one owes the guest a {@code RefundRequested}, and
   * after the UPDATE the old status is gone.
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
          WHERE id = :id AND status = 'CONFIRMED'
          """,
      nativeQuery = true)
  int cancelConfirmed(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Takes the booking into PAYING and answers whether it did (rule DB-5). {@code
   * payment_started_at} is stamped by the same statement, so a row is never PAYING without the
   * moment its charge was started - which is what the reconciliation sweep reads to find a charge
   * that never came back.
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          UPDATE booking
          SET status = 'PAYING',
              payment_started_at = CAST(:now AS timestamptz),
              updated_at = CAST(:now AS timestamptz)
          WHERE id = :id AND status = 'HELD'
          """,
      nativeQuery = true)
  int startPaying(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Confirms a booking whose charge was approved and answers whether it did (rule DB-5). The {@code
   * status = 'PAYING'} is the transition: a row a concurrent request has already confirmed or
   * cancelled keeps what it has, so only one caller writes the {@code BookingConfirmed}.
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          UPDATE booking
          SET status = 'CONFIRMED',
              updated_at = CAST(:now AS timestamptz)
          WHERE id = :id AND status = 'PAYING'
          """,
      nativeQuery = true)
  int confirmPaying(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Cancels a booking whose charge was declined and answers whether it did (rule DB-5). The reason
   * is the {@code PAYMENT_DECLINED} of the domain, which is also the name the contract uses (D3).
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          UPDATE booking
          SET status = 'CANCELLED',
              cancel_reason = 'PAYMENT_DECLINED',
              updated_at = CAST(:now AS timestamptz)
          WHERE id = :id AND status = 'PAYING'
          """,
      nativeQuery = true)
  int declinePaying(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Leases up to {@code batchSize} bookings whose charge never came back, and answers the ones it
   * took, so the caller can ask the provider again outside a transaction (rule DB-5).
   *
   * <p>The CTE picks the batch, driven by {@code booking_status_payment_started_at_idx}, and locks
   * it: {@code SKIP LOCKED} passes by a row another transaction is writing instead of waiting for
   * it. A CTE rather than {@code id IN (SELECT ... LIMIT ...)} because PostgreSQL may plan the
   * subquery as a re-scanned semi-join, which takes more rows than the batch.
   *
   * <p>The UPDATE re-stamps {@code payment_started_at}, which is what makes this a lease rather
   * than a read: the row's age starts again from {@code now}, so no other instance and no later
   * sweep picks it up until the stale age has passed once more. The lock alone could not do that,
   * because the charge deliberately happens after this transaction has committed. The conditional
   * transitions remain the real guard - a charge is idempotent on the booking id, so a sweep that
   * does race another one asks the same question and loses the UPDATE.
   */
  @Transactional
  @Query(
      value =
          """
          WITH stale AS (
            SELECT id FROM booking
            WHERE status = 'PAYING'
              AND payment_started_at <= CAST(:startedBefore AS timestamptz)
            ORDER BY payment_started_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
          )
          UPDATE booking
          SET payment_started_at = CAST(:now AS timestamptz),
              updated_at = CAST(:now AS timestamptz)
          FROM stale
          WHERE booking.id = stale.id AND booking.status = 'PAYING'
          RETURNING booking.id
          """,
      nativeQuery = true)
  List<UUID> leaseStalePayments(
      @Param("now") Instant now,
      @Param("startedBefore") Instant startedBefore,
      @Param("batchSize") int batchSize);
}
