package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.Booking;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

  /** The booking a guest created with this idempotency key, if the key was used (rule I-1). */
  Optional<Booking> findByGuestIdAndIdempotencyKey(UUID guestId, String idempotencyKey);

  /**
   * Whether a booking that still holds the room covers a night of the stay: the statuses and the
   * half-open overlap of {@code booking_no_overlap}, asked by the application (D2).
   */
  default boolean existsActiveOverlap(UUID roomId, LocalDate checkIn, LocalDate checkOut) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
