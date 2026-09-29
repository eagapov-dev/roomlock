package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record BookingResponse(
    UUID id,
    UUID roomId,
    UUID guestId,
    LocalDate checkIn,
    LocalDate checkOut,
    BookingStatus status,
    Instant holdExpiresAt,
    BigDecimal totalPrice) {

  static BookingResponse of(Booking booking) {
    return new BookingResponse(
        booking.getId(),
        booking.getRoomId(),
        booking.getGuestId(),
        booking.getStay().checkIn(),
        booking.getStay().checkOut(),
        booking.getStatus(),
        booking.getHoldExpiresAt(),
        booking.getTotalPrice().setScale(2, RoundingMode.UNNECESSARY));
  }
}
