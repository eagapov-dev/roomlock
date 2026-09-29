package dev.roomlock.payment.payments;

import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * What {@link ChargeRule} decided about one charge: approved, or declined with the reason the
 * caller is told.
 */
public record ChargeDecision(boolean approved, @Nullable String declineReason) {

  private static final ChargeDecision APPROVED = new ChargeDecision(true, null);

  public ChargeDecision {
    if (approved == (declineReason != null)) {
      throw new IllegalArgumentException(
          "A declined charge carries a reason, an approved one none");
    }
  }

  public static ChargeDecision approve() {
    return APPROVED;
  }

  public static ChargeDecision decline(String reason) {
    return new ChargeDecision(false, Objects.requireNonNull(reason, "reason"));
  }

  /** The reason of a declined charge; never called for an approved one. */
  public String requiredDeclineReason() {
    return Objects.requireNonNull(declineReason, "declineReason");
  }
}
