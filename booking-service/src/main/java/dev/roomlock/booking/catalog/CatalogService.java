package dev.roomlock.booking.catalog;

import dev.roomlock.booking.domain.Hotel;
import dev.roomlock.booking.domain.Room;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Hotel and room catalog use cases. */
@Service
public class CatalogService {

  public Hotel createHotel(CreateHotelCommand command) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  public Hotel getHotel(UUID hotelId) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  public AddRoomResult addRoom(AddRoomCommand command) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  public Room getRoom(UUID roomId) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
