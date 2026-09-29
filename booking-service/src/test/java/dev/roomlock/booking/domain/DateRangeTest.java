package dev.roomlock.booking.domain;

import static dev.roomlock.booking.support.TestData.november;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRangeTest {

  @Test
  void shouldThrowIllegalArgumentExceptionWhenCheckOutEqualsCheckIn() {
    // given
    LocalDate checkIn = november(10);

    // when / then
    assertThatThrownBy(() -> new DateRange(checkIn, checkIn))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalArgumentExceptionWhenCheckOutBeforeCheckIn() {
    // given
    LocalDate checkIn = november(13);
    LocalDate checkOut = november(10);

    // when / then
    assertThatThrownBy(() -> new DateRange(checkIn, checkOut))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalArgumentExceptionWhenCheckInIsNull() {
    // given
    LocalDate checkOut = november(13);

    // when / then
    assertThatThrownBy(() -> new DateRange(null, checkOut))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalArgumentExceptionWhenCheckOutIsNull() {
    // given
    LocalDate checkIn = november(10);

    // when / then
    assertThatThrownBy(() -> new DateRange(checkIn, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldCountNightsWhenRangeIsValid() {
    // given
    DateRange range = new DateRange(november(10), november(13));

    // when
    long nights = range.nights();

    // then
    assertThat(nights).isEqualTo(3);
  }

  @Test
  void shouldCountNightsAcrossMonthBoundaryWhenRangeIsValid() {
    // given
    DateRange range = new DateRange(november(29), november(29).plusDays(4));

    // when
    long nights = range.nights();

    // then
    assertThat(nights).isEqualTo(4);
  }
}
