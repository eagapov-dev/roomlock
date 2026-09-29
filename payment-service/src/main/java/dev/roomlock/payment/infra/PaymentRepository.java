package dev.roomlock.payment.infra;

import dev.roomlock.payment.domain.Payment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Recorded payments, one per booking. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  /** The payment of the booking, empty while the booking has never been charged. */
  Optional<Payment> findByBookingId(UUID bookingId);

  /**
   * Records the payment unless the booking already has one.
   *
   * @return 1 if this call decided the outcome, 0 if another call had decided it first
   */
  default int insertIfBookingFree(Payment payment) {
    return insertUnlessBookingCharged(
        payment.getId(),
        payment.getBookingId(),
        payment.getGuestId(),
        payment.getAmountMinor(),
        payment.getCurrency(),
        payment.getStatus().name(),
        payment.getDeclineReason().orElse(null),
        payment.getChargedAt());
  }

  /**
   * One statement instead of a check and an insert: the unique {@code booking_id} decides, so two
   * concurrent charges of one booking cannot both be recorded (rule DB-5).
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO payment (id, booking_id, guest_id, amount_minor, currency, status,
                               decline_reason, charged_at)
          VALUES (:id, :bookingId, :guestId, :amountMinor, :currency, :status, :declineReason,
                  CAST(:chargedAt AS timestamptz))
          ON CONFLICT (booking_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertUnlessBookingCharged(
      @Param("id") UUID id,
      @Param("bookingId") UUID bookingId,
      @Param("guestId") UUID guestId,
      @Param("amountMinor") long amountMinor,
      @Param("currency") String currency,
      @Param("status") String status,
      @Param("declineReason") String declineReason,
      @Param("chargedAt") Instant chargedAt);

  /**
   * Turns an approved payment into a refunded one. A conditional {@code UPDATE} rather than a read
   * and a write (rule DB-5): only one of two concurrent refunds of one booking can match a row that
   * is still {@code APPROVED}.
   *
   * @return 1 if this call gave the money back, 0 if there was nothing to refund
   */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE payment
          SET status = 'REFUNDED', refunded_at = CAST(:refundedAt AS timestamptz)
          WHERE booking_id = :bookingId AND status = 'APPROVED'
          """,
      nativeQuery = true)
  int markRefunded(@Param("bookingId") UUID bookingId, @Param("refundedAt") Instant refundedAt);
}
