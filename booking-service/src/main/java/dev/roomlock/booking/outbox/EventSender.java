package dev.roomlock.booking.outbox;

import dev.roomlock.booking.domain.OutboxEvent;

/**
 * Where a published event goes. A port, so the publisher can be driven without a broker and the
 * broker adapter can be tested without the publisher (rule ST-6).
 */
public interface EventSender {

  /**
   * Sends the event and returns once the broker has acknowledged it, or throws if it has not.
   *
   * @throws EventSendFailedException the event stays unpublished and is sent again later
   */
  void send(OutboxEvent event);
}
