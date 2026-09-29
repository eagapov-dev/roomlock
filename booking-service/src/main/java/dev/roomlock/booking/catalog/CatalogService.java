package dev.roomlock.booking.catalog;

import dev.roomlock.booking.domain.Hotel;
import dev.roomlock.booking.domain.ResourceNotFoundException;
import dev.roomlock.booking.domain.Room;
import dev.roomlock.booking.infra.HotelRepository;
import dev.roomlock.booking.infra.RoomRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hotel and room catalog use cases. */
@Service
public class CatalogService {

  private static final String HOTEL = "hotel";
  private static final String ROOM = "room";

  private final HotelRepository hotels;
  private final RoomRepository rooms;

  public CatalogService(HotelRepository hotels, RoomRepository rooms) {
    this.hotels = hotels;
    this.rooms = rooms;
  }

  @Transactional
  public Hotel createHotel(CreateHotelCommand command) {
    return hotels.save(Hotel.create(command.name(), command.city()));
  }

  @Transactional(readOnly = true)
  public Hotel getHotel(UUID hotelId) {
    return hotels
        .findById(hotelId)
        .orElseThrow(() -> new ResourceNotFoundException(HOTEL, hotelId));
  }

  /**
   * Adds a room to the hotel. The hotel has to exist (rule E-4); a number already used in it is a
   * business outcome, decided by the unique index inside the insert.
   */
  @Transactional
  public AddRoomResult addRoom(AddRoomCommand command) {
    if (!hotels.existsById(command.hotelId())) {
      throw new ResourceNotFoundException(HOTEL, command.hotelId());
    }
    Room room =
        Room.create(
            command.hotelId(), command.number(), command.capacity(), command.pricePerNight());
    if (rooms.insertIfNumberFree(room) == 0) {
      return new AddRoomResult.NumberTaken(command.hotelId(), command.number());
    }
    return new AddRoomResult.Added(room);
  }

  @Transactional(readOnly = true)
  public Room getRoom(UUID roomId) {
    return rooms.findById(roomId).orElseThrow(() -> new ResourceNotFoundException(ROOM, roomId));
  }
}
