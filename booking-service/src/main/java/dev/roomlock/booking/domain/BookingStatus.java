package dev.roomlock.booking.domain;

/** Booking lifecycle status, stored as its name (rule DB-1). */
public enum BookingStatus {
  HELD,
  PAYING,
  CONFIRMED,
  CANCELLED
}
