package dev.roomlock.payment.payments;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Charges and refunds as Grafana sees them: what was decided, and how long deciding took. */
@Component
public class PaymentMetrics {

  public PaymentMetrics(MeterRegistry registry) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  ChargeResult timed(java.util.function.Supplier<ChargeResult> charge) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  void charged(ChargeResult result) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  void refunded(RefundResult result) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
