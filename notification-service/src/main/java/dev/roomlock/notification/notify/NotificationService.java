package dev.roomlock.notification.notify;

import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.infra.NotificationRepository;
import dev.roomlock.notification.infra.SqlState;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Notifies the guest about one booking event, at most once per event.
 *
 * <p>Kafka gives at-least-once delivery, so the event id is the deduplication key. A redelivery the
 * recorded delivery already covers is skipped here; a delivery that loses the race for the claim to
 * a concurrent consumer is skipped once its transaction has rolled back. Neither is a failure of
 * this service, and neither may be retried or parked (rule K-3).
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationRepository notifications;
  private final NotificationMessages messages;
  private final NotificationDelivery delivery;

  public NotificationService(
      NotificationRepository notifications,
      NotificationMessages messages,
      NotificationDelivery delivery) {
    this.notifications = notifications;
    this.messages = messages;
    this.delivery = delivery;
  }

  /** Delivers the notification of the event, or does nothing if the event is already handled. */
  public void notifyGuest(BookingEvent event) {
    Optional<GuestNotification> rendered = messages.render(event);
    if (rendered.isEmpty()) {
      log.debug("No guest notification for {} of booking {}", event.type(), event.bookingId());
      return;
    }
    if (notifications.existsByEventId(event.eventId())) {
      log.info(
          "Event {} ({} of booking {}) was already delivered, skipping the redelivery",
          event.eventId(),
          event.type(),
          event.bookingId());
      return;
    }
    deliverOnce(event, rendered.get());
  }

  private void deliverOnce(BookingEvent event, GuestNotification message) {
    try {
      delivery.deliver(event, message);
    } catch (DataIntegrityViolationException violation) {
      if (!SqlState.UNIQUE_VIOLATION.thrownBy(violation)) {
        throw violation;
      }
      log.info(
          "Event {} ({} of booking {}) was claimed by another delivery, skipping this one",
          event.eventId(),
          event.type(),
          event.bookingId());
    }
  }
}
