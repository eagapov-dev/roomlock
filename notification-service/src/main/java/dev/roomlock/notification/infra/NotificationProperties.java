package dev.roomlock.notification.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Consumer settings of the service, all of them in {@code application.yml} (task 3).
 *
 * @param topic the booking events topic, {@code Topics.BOOKING_EVENTS} of the contract
 * @param deadLetterSuffix appended to the topic a record is parked under when it cannot be handled
 * @param retry how often handling a record is retried before it is parked
 */
@ConfigurationProperties("roomlock.notifications")
record NotificationProperties(String topic, String deadLetterSuffix, Retry retry) {

  /**
   * @param attempts redeliveries after the first failure; 0 parks a failed record at once
   * @param backoff wait between two attempts
   */
  record Retry(int attempts, Duration backoff) {}
}
