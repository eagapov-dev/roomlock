package dev.roomlock.notification.domain;

/**
 * How a notification reached the guest, recorded with every delivery.
 *
 * <p>Only {@link #LOG} has an adapter today; {@link #EMAIL} and {@link #SMS} name the channels a
 * real sender would add without a schema change.
 */
public enum NotificationChannel {
  LOG,
  EMAIL,
  SMS
}
