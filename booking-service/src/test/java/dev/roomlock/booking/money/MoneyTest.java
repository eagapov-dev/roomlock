package dev.roomlock.booking.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The conversion a wrong factor would turn into a 100x overcharge: a booking carries {@code
 * numeric(12,2)} and the payment contract takes minor units.
 */
class MoneyTest {

  @ParameterizedTest
  @CsvSource({
    "450.00, 45000",
    "0.01, 1",
    "0.00, 0",
    "1, 100",
    "1.5, 150",
    "999.99, 99999",
    "1000.00, 100000",
    "9999999999.99, 999999999999"
  })
  void shouldConvertToMinorUnitsWhenAmountFitsThem(BigDecimal amount, long expected) {
    // given
    // when
    long minor = Money.toMinorUnits(amount);

    // then
    assertThat(minor).isEqualTo(expected);
  }

  @Test
  void shouldKeepTrailingZeroesOutOfTheResultWhenScaleIsLargerThanAMinorUnit() {
    // given
    BigDecimal amount = new BigDecimal("450.0000");

    // when
    long minor = Money.toMinorUnits(amount);

    // then
    assertThat(minor).isEqualTo(45_000);
  }

  @Test
  void shouldFailWhenAmountHasAFractionSmallerThanAMinorUnit() {
    // given
    BigDecimal amount = new BigDecimal("450.001");

    // when
    // then
    assertThatExceptionOfType(ArithmeticException.class)
        .isThrownBy(() -> Money.toMinorUnits(amount));
  }
}
