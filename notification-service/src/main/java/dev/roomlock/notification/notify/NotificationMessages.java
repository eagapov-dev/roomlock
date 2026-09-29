package dev.roomlock.notification.notify;

import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.notification.domain.GuestNotification;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Turns a booking event into the text the guest reads. */
@Component
public class NotificationMessages {

  /** The message for the event, or empty if the event is not one a guest is told about. */
  public Optional<GuestNotification> render(BookingEvent event) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
