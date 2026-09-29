package dev.roomlock.notification.support;

import dev.roomlock.notification.delivery.NotificationSender;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.NotificationChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * Sender the tests assert on, in place of the logging one: the port/adapter split is what makes
 * "delivered exactly once" observable without a real channel.
 *
 * <p>Thread-safe, because the listener container delivers on its own thread.
 */
@TestComponent
@Primary
public class RecordingNotificationSender implements NotificationSender {

  private final List<GuestNotification> sent = new CopyOnWriteArrayList<>();

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.LOG;
  }

  @Override
  public void send(GuestNotification notification) {
    sent.add(notification);
  }

  /**
   * Deliveries of one booking since the last {@link #clear()}, in delivery order: counting per
   * booking keeps a record left over by another test from changing the count.
   */
  public List<GuestNotification> sentFor(UUID bookingId) {
    return sent.stream()
        .filter(notification -> notification.bookingId().equals(bookingId))
        .toList();
  }

  public void clear() {
    sent.clear();
  }
}
