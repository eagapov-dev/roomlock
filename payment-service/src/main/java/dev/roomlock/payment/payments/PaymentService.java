package dev.roomlock.payment.payments;

import dev.roomlock.payment.domain.Payment;
import dev.roomlock.payment.infra.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Charging a booking and giving its money back, both idempotent on the booking id (rule I-1).
 *
 * <p>The first charge of a booking decides, records and answers; every later one is answered with
 * the stored payment without charging again, and a call that carries a different amount, currency
 * or guest under the same booking id is refused instead (rule I-2). A refund is the same story from
 * the other side: the first one moves the payment to {@code REFUNDED}, every later one is told that
 * the money is already back.
 *
 * <p>Neither path reads and then writes: the charge inserts conditionally on the booking id being
 * free and the refund updates conditionally on the payment still being {@code APPROVED}, so
 * concurrent calls - a retried gRPC call and a redelivered {@code RefundRequested} at the same
 * moment - cannot both take effect (rule DB-5).
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepository payments;
  private final ChargeRule rule;
  private final PaymentMetrics metrics;
  private final Clock clock;

  PaymentService(PaymentRepository payments, ChargeRule rule, PaymentMetrics metrics, Clock clock) {
    this.payments = payments;
    this.rule = rule;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Charges the booking, or answers what the charge of this booking answered the first time. */
  @Transactional
  public ChargeResult charge(ChargeCommand command) {
    ChargeResult result = metrics.timed(() -> decide(command));
    metrics.charged(result);
    return result;
  }

  /** Gives the money of the booking back, or says why there is nothing to give back. */
  @Transactional
  public RefundResult refund(RefundCommand command) {
    RefundResult result = giveBack(command);
    metrics.refunded(result);
    return result;
  }

  private ChargeResult decide(ChargeCommand command) {
    Optional<Payment> recorded = payments.findByBookingId(command.bookingId());
    if (recorded.isPresent()) {
      return replayOf(recorded.get(), command);
    }
    Payment payment = decidedPayment(command);
    if (payments.insertIfBookingFree(payment) == 0) {
      return replayOfRace(command);
    }
    log.info(
        "Charge of booking {} is {}: payment {}, {} {}",
        command.bookingId(),
        payment.getStatus(),
        payment.getId(),
        command.amountMinor(),
        command.currency());
    return new ChargeResult.Charged(payment);
  }

  private RefundResult giveBack(RefundCommand command) {
    Instant now = clock.instant();
    boolean refundedNow = payments.markRefunded(command.bookingId(), now) == 1;
    Optional<Payment> recorded = payments.findByBookingId(command.bookingId());
    if (recorded.isEmpty()) {
      log.warn("Nothing to refund: booking {} was never charged", command.bookingId());
      return new RefundResult.NotCharged(command.bookingId());
    }
    Payment payment = recorded.get();
    if (refundedNow) {
      log.info("Refunded payment {} of booking {}", payment.getId(), command.bookingId());
      return new RefundResult.Refunded(payment);
    }
    return notRefundedNow(payment);
  }

  private Payment decidedPayment(ChargeCommand command) {
    ChargeDecision decision = rule.decide(command);
    Instant now = clock.instant();
    if (decision.approved()) {
      return Payment.approved(
          command.bookingId(), command.guestId(), command.amountMinor(), command.currency(), now);
    }
    return Payment.declined(
        command.bookingId(),
        command.guestId(),
        command.amountMinor(),
        command.currency(),
        decision.requiredDeclineReason(),
        now);
  }

  /** A repeated charge is a replay only when it asks for the same thing (rule I-2). */
  private static ChargeResult replayOf(Payment payment, ChargeCommand command) {
    Optional<ChargeMismatch> mismatch = mismatchOf(payment, command);
    if (mismatch.isPresent()) {
      log.warn(
          "Booking {} was charged {} {} for guest {}, and is now charged {} {} for guest {}: {}",
          payment.getBookingId(),
          payment.getAmountMinor(),
          payment.getCurrency(),
          payment.getGuestId(),
          command.amountMinor(),
          command.currency(),
          command.guestId(),
          mismatch.get());
      return new ChargeResult.Mismatched(payment, mismatch.get());
    }
    return new ChargeResult.AlreadyCharged(payment);
  }

  private static Optional<ChargeMismatch> mismatchOf(Payment payment, ChargeCommand command) {
    if (!payment.getGuestId().equals(command.guestId())) {
      return Optional.of(ChargeMismatch.GUEST);
    }
    if (payment.getAmountMinor() != command.amountMinor()) {
      return Optional.of(ChargeMismatch.AMOUNT);
    }
    if (!payment.getCurrency().equals(command.currency())) {
      return Optional.of(ChargeMismatch.CURRENCY);
    }
    return Optional.empty();
  }

  /** Another charge of the same booking won the race; its payment is the answer to this one too. */
  private ChargeResult replayOfRace(ChargeCommand command) {
    return payments
        .findByBookingId(command.bookingId())
        .map(payment -> replayOf(payment, command))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Payment of booking "
                        + command.bookingId()
                        + " was inserted and then removed"));
  }

  private static RefundResult notRefundedNow(Payment payment) {
    return switch (payment.getStatus()) {
      case REFUNDED -> new RefundResult.AlreadyRefunded(payment);
      case DECLINED -> declinedCharge(payment);
      // The conditional UPDATE matches every APPROVED payment, so this row cannot still be one.
      case APPROVED ->
          throw new IllegalStateException(
              "Payment " + payment.getId() + " is APPROVED but was not refunded");
    };
  }

  private static RefundResult declinedCharge(Payment payment) {
    log.warn(
        "Nothing to refund: the charge of booking {} was declined ({})",
        payment.getBookingId(),
        payment.getDeclineReason().orElse(""));
    return new RefundResult.NotApproved(payment);
  }
}
