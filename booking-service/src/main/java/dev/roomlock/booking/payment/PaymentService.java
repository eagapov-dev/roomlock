package dev.roomlock.booking.payment;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.money.Money;
import dev.roomlock.booking.money.MoneyProperties;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Paying for a booking: HELD becomes PAYING, the provider decides, and PAYING becomes CONFIRMED or
 * CANCELLED with PAYMENT_DECLINED.
 *
 * <p>Not transactional on purpose. Each transition is a transaction of {@link PaymentTransitions},
 * and the charge sits between them: a round trip to the provider inside a transaction would hold a
 * connection of a pool of ten for its whole duration, which is how a slow acquirer takes a service
 * down.
 *
 * <p>Nothing is lost by committing PAYING first. A charge whose answer never arrives leaves the row
 * PAYING with {@code payment_started_at} set, and {@link PaymentReconciler} asks the provider
 * again: the booking id is the idempotency key, so the second question gets the first answer.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentTransitions transitions;
  private final PaymentGateway payments;
  private final String currency;
  private final Clock clock;

  public PaymentService(
      PaymentTransitions transitions, PaymentGateway payments, MoneyProperties money, Clock clock) {
    this.transitions = transitions;
    this.payments = payments;
    this.currency = money.currency();
    this.clock = clock;
  }

  /**
   * Pays for the booking. The conditional UPDATE decides where the flow goes: it either wins and
   * the charge follows, or the row is read back to say why it did not (rule J-2).
   */
  public PayResult pay(UUID bookingId) {
    if (transitions.startPaying(bookingId, clock.instant())) {
      return chargeAndApply(transitions.get(bookingId));
    }
    Booking booking = transitions.get(bookingId);
    return switch (booking.getStatus()) {
      case CONFIRMED -> new PayResult.AlreadyConfirmed(booking);
      // A charge of an earlier request never came back. Ask again rather than refuse: the answer is
      // the one the provider already gave, and this is the retry a 503 asked the client for.
      case PAYING -> chargeAndApply(booking);
      // HELD cannot reach this branch: the UPDATE waits for a concurrent writer and then matches.
      case CANCELLED, HELD -> new PayResult.NotPayable(bookingId, booking.getStatus());
    };
  }

  /**
   * Asks the provider and applies its answer. Outside every transaction, which is the whole reason
   * this method exists separately from the transitions it calls.
   */
  PayResult chargeAndApply(Booking booking) {
    UUID bookingId = booking.getId();
    return switch (payments.charge(orderOf(booking))) {
      case ChargeOutcome.Approved approved -> confirmed(bookingId);
      case ChargeOutcome.Declined(String reason) -> declined(bookingId, reason);
      case ChargeOutcome.Unavailable(String detail) -> {
        log.warn("Booking {} stays PAYING, the provider gave no answer: {}", bookingId, detail);
        yield new PayResult.ProviderUnavailable(bookingId);
      }
    };
  }

  private PayResult confirmed(UUID bookingId) {
    Booking booking = transitions.confirm(bookingId, clock.instant());
    return switch (booking.getStatus()) {
      case CONFIRMED -> new PayResult.Confirmed(booking);
      // The provider approved a booking something else has meanwhile cancelled. Nothing to undo
      // here: the cancellation asks for the refund itself (rule K-1).
      case HELD, PAYING, CANCELLED -> new PayResult.NotPayable(bookingId, booking.getStatus());
    };
  }

  private PayResult declined(UUID bookingId, String reason) {
    Booking booking = transitions.decline(bookingId, clock.instant());
    return switch (booking.getStatus()) {
      case CANCELLED -> new PayResult.Declined(bookingId, reason);
      // Confirmed by a concurrent caller that asked the provider before it started declining.
      case CONFIRMED -> new PayResult.AlreadyConfirmed(booking);
      case HELD, PAYING -> new PayResult.NotPayable(bookingId, booking.getStatus());
    };
  }

  private ChargeOrder orderOf(Booking booking) {
    return new ChargeOrder(
        booking.getId(),
        booking.getGuestId(),
        Money.toMinorUnits(booking.getTotalPrice()),
        currency);
  }
}
