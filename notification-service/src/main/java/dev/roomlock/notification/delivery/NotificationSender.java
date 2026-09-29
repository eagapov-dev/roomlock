package dev.roomlock.notification.delivery;

import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.NotificationChannel;

/**
 * Port every delivery channel implements. An e-mail or SMS adapter replaces the logging one without
 * touching the consumer: the use case only knows this interface.
 *
 * <p>A sender that cannot deliver throws; the caller then records no delivery and the event is
 * redelivered.
 */
public interface NotificationSender {

  /** The channel the sender delivers on, stored with every delivery. */
  NotificationChannel channel();

  /** Delivers the message, or throws if the channel refuses it. */
  void send(GuestNotification notification);
}
