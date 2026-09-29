package dev.roomlock.notification.notify;

import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.notification.delivery.NotificationSender;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.Notification;
import dev.roomlock.notification.infra.NotificationRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One delivery, in one transaction: the row is claimed and flushed before the sender runs, so the
 * unique {@code event_id} rejects the claim of a concurrent duplicate, and a send that throws rolls
 * the claim back with it - the event stays unhandled and its redelivery notifies for real.
 *
 * <p>Separate from {@link NotificationService} because the violation of that unique key has to be
 * read <em>after</em> this transaction rolled back, never inside the transaction that broke.
 */
@Component
class NotificationDelivery {

  private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);

  private final NotificationRepository notifications;
  private final NotificationSender sender;
  private final Clock clock;

  NotificationDelivery(
      NotificationRepository notifications, NotificationSender sender, Clock clock) {
    this.notifications = notifications;
    this.sender = sender;
    this.clock = clock;
  }

  @Transactional
  Notification deliver(BookingEvent event, GuestNotification message) {
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
    return claim;
  }
}
