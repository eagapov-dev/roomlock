package dev.roomlock.booking.catalog;

import dev.roomlock.booking.domain.Room;
import java.util.UUID;

/**
 * Business outcomes of adding a room (rule J-2). An unknown hotel is not an outcome here: it is a
 * {@link dev.roomlock.booking.domain.ResourceNotFoundException} of the CRUD path (rule E-4).
 */
public sealed interface AddRoomResult permits AddRoomResult.Added, AddRoomResult.NumberTaken {

  record Added(Room room) implements AddRoomResult {}

  /** The hotel already has a room with this number. */
  record NumberTaken(UUID hotelId, String number) implements AddRoomResult {}
}
