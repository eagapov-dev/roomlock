package dev.roomlock.booking.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversion between the two shapes money has in this service: a booking stores {@code
 * numeric(12,2)}, the payment contract takes minor units (grosze for PLN).
 *
 * <p>One place, because the factor is the kind of mistake nothing downstream notices: a charge of
 * 450 instead of 45000 undercharges a guest by 100x, and the other way round overcharges by 100x.
 */
public final class Money {

  /** Decimal digits of a minor unit: 1 PLN is 100 grosze, as for every currency of this project. */
  private static final int MINOR_UNIT_SCALE = 2;

  private Money() {}

  /**
   * The amount in minor units.
   *
   * @throws ArithmeticException the amount carries a fraction smaller than a minor unit, so no
   *     exact number of them exists; rounding here would change a price nobody asked to change
   */
  public static long toMinorUnits(BigDecimal amount) {
    return amount
        .setScale(MINOR_UNIT_SCALE, RoundingMode.UNNECESSARY)
        .movePointRight(MINOR_UNIT_SCALE)
        .longValueExact();
  }
}
