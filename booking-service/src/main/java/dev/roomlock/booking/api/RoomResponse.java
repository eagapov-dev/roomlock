package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.Room;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

record RoomResponse(UUID id, UUID hotelId, String number, int capacity, BigDecimal pricePerNight) {

  /** The price is always two decimals in the response, as the {@code numeric(12,2)} column is. */
  static RoomResponse of(Room room) {
    return new RoomResponse(
        room.getId(),
        room.getHotelId(),
        room.getNumber(),
        room.getCapacity(),
        room.getPricePerNight().setScale(2, RoundingMode.UNNECESSARY));
  }
}
