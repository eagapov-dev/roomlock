package dev.roomlock.booking.hold;

import dev.roomlock.booking.domain.Booking;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Room hold use cases. */
@Service
public class HoldService {

  public HoldResult hold(HoldCommand command) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  public Booking getBooking(UUID bookingId) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
