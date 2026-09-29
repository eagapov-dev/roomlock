package dev.roomlock.notification.infra;

import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.notification.notify.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the booking events topic, keyed by booking id, and hands every event to the use case.
 *
 * <p>Nothing is caught here on purpose: a failure belongs to the container's error handler, which
 * retries what may succeed later and parks what never will (see {@link KafkaConsumerConfig}).
 */
@Component
class BookingEventsListener {

  private static final Logger log = LoggerFactory.getLogger(BookingEventsListener.class);

  private final BookingEventReader reader;
  private final NotificationService notifications;

  BookingEventsListener(BookingEventReader reader, NotificationService notifications) {
    this.reader = reader;
    this.notifications = notifications;
  }

  @KafkaListener(topics = "${roomlock.notifications.topic}")
  void onBookingEvent(ConsumerRecord<String, String> record) {
    BookingEvent event = reader.read(record);
    log.debug(
        "Handling {} {} from {}-{} offset {}",
        event.type(),
        event.eventId(),
        record.topic(),
        record.partition(),
        record.offset());
    notifications.notifyGuest(event);
  }
}
