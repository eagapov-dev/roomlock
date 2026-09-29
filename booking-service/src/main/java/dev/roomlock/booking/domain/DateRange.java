package dev.roomlock.booking.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Half-open stay range {@code [checkIn, checkOut)}: the check-out day is not a night of the stay.
 *
 * <p>User input is validated before construction (rule V-4); an invalid range here is a bug.
 */
public record DateRange(LocalDate checkIn, LocalDate checkOut) {

  public DateRange {
    if (checkIn == null || checkOut == null) {
      throw new IllegalArgumentException("checkIn and checkOut are required");
    }
    if (!checkOut.isAfter(checkIn)) {
      throw new IllegalArgumentException(
          "checkOut " + checkOut + " must be after checkIn " + checkIn);
    }
  }

  /** Number of nights in the stay. */
  public long nights() {
    return ChronoUnit.DAYS.between(checkIn, checkOut);
  }
}
