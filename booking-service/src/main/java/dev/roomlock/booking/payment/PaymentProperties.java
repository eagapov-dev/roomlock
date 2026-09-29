package dev.roomlock.booking.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How booking-service talks to the payment provider.
 *
 * @param deadline how long a charge may take before the call is given up on. A few seconds: long
 *     enough for an acquirer that is merely slow, short enough that a waiting guest gets an answer
 *     and the request thread is freed. A charge that runs out of it leaves the booking PAYING, and
 *     {@link PaymentReconciler} finishes the job.
 * @param reconcile the sweep that finishes those
 */
@ConfigurationProperties("roomlock.payment")
public record PaymentProperties(Duration deadline, Reconcile reconcile) {

  /**
   * The sweep over bookings whose charge never came back.
   *
   * @param interval delay between the end of one sweep and the start of the next one; written as an
   *     ISO-8601 duration because {@code @Scheduled} reads the same value
   * @param after how long a booking may stay PAYING before the sweep takes it over; longer than
   *     {@link #deadline}, or the sweep would race the request that is still waiting
   * @param batchSize bookings one sweep charges at most, so a backlog never grows one transaction
   */
  public record Reconcile(Duration interval, Duration after, int batchSize) {}
}
