package dev.roomlock.booking.domain;

import java.time.LocalDate;

/** Half-open stay range {@code [checkIn, checkOut)}; skeleton, invariants come in phase B. */
public record DateRange(LocalDate checkIn, LocalDate checkOut) {

  /** Number of nights in the stay. */
  public long nights() {
    throw new UnsupportedOperationException("DateRange.nights is not implemented yet");
  }
}
