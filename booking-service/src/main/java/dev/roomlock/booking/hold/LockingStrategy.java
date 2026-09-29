package dev.roomlock.booking.hold;

/**
 * The three ways the service keeps two guests from holding the same room for the same night; the
 * active one is {@code roomlock.locking.strategy} (D2).
 */
public enum LockingStrategy {

  /** {@code SELECT ... FOR UPDATE} on the room row, then the overlap check. */
  PESSIMISTIC,

  /** Overlap check, then a conditional bump of {@code room.version}; a lost bump is retried. */
  OPTIMISTIC,

  /** No application check: the {@code booking_no_overlap} exclusion constraint decides (D1). */
  CONSTRAINT
}
