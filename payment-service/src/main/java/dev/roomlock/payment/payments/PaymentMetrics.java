package dev.roomlock.payment.payments;

import dev.roomlock.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Charges and refunds as Grafana sees them: what was decided, and how long deciding took.
 *
 * <p>Both counters carry every outcome from the moment the bean is created, so a panel has a series
 * to draw from the first scrape instead of a gap until the first charge of that kind.
 */
@Component
class PaymentMetrics {

  /** Charges by what the service answered; the rate of this counter is the charge throughput. */
  static final String CHARGES = "roomlock.payment.charges";

  /** Refunds by what the service answered, whichever caller asked (gRPC or the refund topic). */
  static final String REFUNDS = "roomlock.payment.refunds";

  /** Duration of deciding one charge, the part of a client's latency this service owns. */
  static final String DURATION = "roomlock.payment.charge.duration";

  static final String OUTCOME_TAG = "outcome";

  private final Map<ChargeOutcome, Counter> charges;
  private final Map<RefundOutcome, Counter> refunds;
  private final Timer duration;

  PaymentMetrics(MeterRegistry registry) {
    this.charges =
        counters(registry, ChargeOutcome.values(), CHARGES, "Charges by what the service answered");
    this.refunds =
        counters(registry, RefundOutcome.values(), REFUNDS, "Refunds by what the service answered");
    this.duration =
        Timer.builder(DURATION)
            .description("Duration of deciding one charge")
            .publishPercentileHistogram()
            .register(registry);
  }

  /** Times the charge and hands its outcome back untouched. */
  ChargeResult timed(Supplier<ChargeResult> charge) {
    return duration.record(charge);
  }

  void charged(ChargeResult result) {
    charges.get(ChargeOutcome.of(result)).increment();
  }

  void refunded(RefundResult result) {
    refunds.get(RefundOutcome.of(result)).increment();
  }

  private static <T extends Enum<T> & Tagged> Map<T, Counter> counters(
      MeterRegistry registry, T[] outcomes, String name, String description) {
    return Arrays.stream(outcomes)
        .collect(
            Collectors.toUnmodifiableMap(
                Function.identity(),
                outcome ->
                    Counter.builder(name)
                        .description(description)
                        .tag(OUTCOME_TAG, outcome.tag())
                        .register(registry)));
  }

  /** Something a tag value can be read off, so the tag never carries a Java name. */
  private interface Tagged {
    String tag();
  }

  /** Tag values of {@link ChargeResult}; a decision is told apart from a replay of one. */
  private enum ChargeOutcome implements Tagged {
    APPROVED,
    DECLINED,
    REPLAYED,
    MISMATCHED;

    static ChargeOutcome of(ChargeResult result) {
      return switch (result) {
        case ChargeResult.Charged charged -> decisionOf(charged.payment().getStatus());
        case ChargeResult.AlreadyCharged alreadyCharged -> REPLAYED;
        case ChargeResult.Mismatched mismatched -> MISMATCHED;
      };
    }

    /** A fresh charge is either of the two decisions; a refunded one cannot be fresh. */
    private static ChargeOutcome decisionOf(PaymentStatus status) {
      return status == PaymentStatus.DECLINED ? DECLINED : APPROVED;
    }

    @Override
    public String tag() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** Tag values of {@link RefundResult}. */
  private enum RefundOutcome implements Tagged {
    REFUNDED,
    ALREADY_REFUNDED,
    NOT_APPROVED,
    NOT_CHARGED;

    static RefundOutcome of(RefundResult result) {
      return switch (result) {
        case RefundResult.Refunded refunded -> REFUNDED;
        case RefundResult.AlreadyRefunded alreadyRefunded -> ALREADY_REFUNDED;
        case RefundResult.NotApproved notApproved -> NOT_APPROVED;
        case RefundResult.NotCharged notCharged -> NOT_CHARGED;
      };
    }

    @Override
    public String tag() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }
}
