package dev.roomlock.booking.lifecycle;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Booking lifecycle use cases that need no payment provider: hold expiry and guest cancellation.
 */
@Service
public class LifecycleService {

  /** Cancels one batch of expired holds and returns how many of them were cancelled. */
  public int expireHolds() {
    throw new UnsupportedOperationException("Hold expiry is not implemented yet");
  }

  /** Cancels the booking on behalf of its guest. */
  public CancelResult cancel(UUID bookingId) {
    throw new UnsupportedOperationException("Cancelling a booking is not implemented yet");
  }
}
