package dev.roomlock.notification.notify;

import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.notification.delivery.NotificationSender;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.Notification;
import dev.roomlock.notification.infra.NotificationRepository;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notifies the guest about one booking event, at most once per event.
 *
 * <p>Kafka gives at-least-once delivery, so the event id is the deduplication key: the recorded
 * delivery is claimed and flushed before the sender is called, and the unique {@code event_id}
 * rejects the claim of a duplicate. A send that throws rolls the claim back with it, so the event
 * is still unhandled and its redelivery notifies the guest for real.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationRepository notifications;
  private final NotificationMessages messages;
  private final NotificationSender sender;
  private final Clock clock;

  public NotificationService(
      NotificationRepository notifications,
      NotificationMessages messages,
      NotificationSender sender,
      Clock clock) {
    this.notifications = notifications;
    this.messages = messages;
    this.sender = sender;
    this.clock = clock;
  }

  /** Delivers the notification of the event, or does nothing if the event was already handled. */
  @Transactional
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
    GuestNotification message = rendered.get();
    Notification claim =
        notifications.saveAndFlush(
            Notification.claim(
                event.eventId(),
                event.type(),
                message,
                sender.channel(),
                event.occurredAt(),
                clock.instant()));
    sender.send(message);
    claim.markSent(clock.instant());
    log.info(
        "Notified guest {} about {} of booking {} on {}",
        message.guestId(),
        event.type(),
        event.bookingId(),
        sender.channel());
  }
}
