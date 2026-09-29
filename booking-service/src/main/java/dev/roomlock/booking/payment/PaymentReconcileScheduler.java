package dev.roomlock.booking.payment;

import dev.roomlock.booking.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the sweep over bookings left PAYING. Off under the {@code test} profile (rule SP-9):
 * tests move the clock and call the use case instead of waiting for a trigger (rule T-7).
 */
@Component
@Profile("!" + Profiles.TEST)
class PaymentReconcileScheduler {

  private static final Logger log = LoggerFactory.getLogger(PaymentReconcileScheduler.class);

  private final PaymentReconciler reconciler;

  PaymentReconcileScheduler(PaymentReconciler reconciler) {
    this.reconciler = reconciler;
  }

  /** One sweep per interval, counted from the end of the previous one. */
  @Scheduled(fixedDelayString = "${roomlock.payment.reconcile.interval}")
  void reconcile() {
    int settled = reconciler.reconcileBatch();
    if (settled > 0) {
      log.info("Payment reconciliation settled {} booking(s)", settled);
    }
  }
}
