package dev.roomlock.notification.domain;

/**
 * What a guest is told about. One kind per booking event the service notifies about; the names are
 * the ones the {@code notification.kind} CHECK allows.
 */
public enum NotificationKind {
  /** A room is held and waits for payment. */
  HOLD,

  /** The stay is paid for and the room belongs to the guest. */
  CONFIRMATION,

  /** The booking lost its room, for one of the cancellation reasons. */
  CANCELLATION
}
