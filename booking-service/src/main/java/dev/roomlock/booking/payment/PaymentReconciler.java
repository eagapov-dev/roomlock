package dev.roomlock.booking.payment;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.infra.BookingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finishes the bookings whose charge never came back.
 *
 * <p>A booking left PAYING is the one state nobody can resolve from the database alone: the money
 * may or may not have moved. Asking {@code Charge} again settles it, because the booking id is the
 * idempotency key - payment-service answers what it decided the first time, or decides now if
 * nothing was ever charged (rule I-1). The answer is then applied through the same conditional
 * transitions and writes the same events as a request would (rules DB-5, K-1).
 *
 * <p>Not transactional, for the same reason {@link PaymentService} is not: the charge is a network
 * round trip and must not happen while a connection of a pool of ten is held. The batch is leased
 * first, in a transaction of its own, and only then charged.
 */
@Component
public class PaymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

  private final BookingRepository bookings;
  private final PaymentService payments;
  private final Duration staleAfter;
  private final int batchSize;
  private final Clock clock;

  public PaymentReconciler(
      BookingRepository bookings,
      PaymentService payments,
      PaymentProperties properties,
      Clock clock) {
    this.bookings = bookings;
    this.payments = payments;
    this.staleAfter = properties.reconcile().after();
    this.batchSize = properties.reconcile().batchSize();
    this.clock = clock;
  }

  /**
   * Reconciles one batch and returns how many bookings it settled - confirmed or cancelled; a
   * provider that is still not answering settles nothing and the booking waits for the next sweep.
   *
   * <p>One lease per sweep, not per backlog, so a pile-up never grows one transaction. Several
   * instances may sweep at once: the lease skips a row another one is writing and pushes the age of
   * the rows it took forward, and the conditional transitions are the guard that makes a race
   * harmless anyway.
   */
  public int reconcileBatch() {
    Instant now = clock.instant();
    List<UUID> leased = bookings.leaseStalePayments(now, now.minus(staleAfter), batchSize);
    int settled = 0;
    for (UUID bookingId : leased) {
      if (settle(bookingId)) {
        settled++;
      }
    }
    return settled;
  }

  /** Whether the booking is no longer PAYING once the provider's answer has been applied. */
  private boolean settle(UUID bookingId) {
    Booking booking = bookings.findById(bookingId).orElse(null);
    if (booking == null) {
      // Truncated or deleted between the lease and here; nothing left to settle.
      return false;
    }
    PayResult result = payments.chargeAndApply(booking);
    log.info("Reconciled booking {} left PAYING: {}", bookingId, result.getClass().getSimpleName());
    return !(result instanceof PayResult.ProviderUnavailable);
  }
}
