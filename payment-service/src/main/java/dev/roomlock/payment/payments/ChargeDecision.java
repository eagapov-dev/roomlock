package dev.roomlock.payment.payments;

import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * What {@link ChargeRule} decided about one charge: approved, or declined with the reason the
 * caller is told.
 */
record ChargeDecision(boolean approved, @Nullable String declineReason) {

  private static final ChargeDecision APPROVED = new ChargeDecision(true, null);

  ChargeDecision {
    if (approved == (declineReason != null)) {
      throw new IllegalArgumentException(
          "A declined charge carries a reason, an approved one none");
    }
  }

  static ChargeDecision approve() {
    return APPROVED;
  }

  static ChargeDecision decline(String reason) {
    return new ChargeDecision(false, Objects.requireNonNull(reason, "reason"));
  }

  /** The reason of a declined charge; never called for an approved one. */
  String requiredDeclineReason() {
    return Objects.requireNonNull(declineReason, "declineReason");
  }
}
