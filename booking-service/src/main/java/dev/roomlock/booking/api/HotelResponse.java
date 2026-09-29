package dev.roomlock.booking.api;

import dev.roomlock.booking.domain.Hotel;
import java.util.UUID;

record HotelResponse(UUID id, String name, String city) {

  static HotelResponse of(Hotel hotel) {
    return new HotelResponse(hotel.getId(), hotel.getName(), hotel.getCity());
  }
}
