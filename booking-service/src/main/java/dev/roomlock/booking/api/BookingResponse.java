package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.Booking;
import dev.roomlock.booking.domain.BookingStatus;
import dev.roomlock.booking.domain.CancelReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** A booking as the API shows it; {@code cancelReason} is set exactly for a CANCELLED booking. */
record BookingResponse(
    UUID id,
    UUID roomId,
    UUID guestId,
    LocalDate checkIn,
    LocalDate checkOut,
    BookingStatus status,
    @Nullable CancelReason cancelReason,
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
        booking.getCancelReason().orElse(null),
        booking.getHoldExpiresAt(),
        booking.getTotalPrice().setScale(2, RoundingMode.UNNECESSARY));
  }
}
