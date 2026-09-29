package dev.roomlock.notification.delivery;

import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only adapter of {@link NotificationSender} today: it writes the message to the log instead of
 * sending it anywhere. An e-mail or SMS adapter replaces this bean without any other change.
 */
@Component
class LoggingNotificationSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.LOG;
  }

  @Override
  public void send(GuestNotification notification) {
    log.info(
        "To guest {} | {} | {} | {}",
        notification.guestId(),
        notification.kind(),
        notification.subject(),
        notification.body());
  }
}
