package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.Room;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, UUID> {

  List<Room> findByHotelId(UUID hotelId);

  /**
   * Inserts the room unless its hotel already has a room with the same number.
   *
   * @return 1 if inserted, 0 if the number is taken in the hotel
   */
  default int insertIfNumberFree(Room room) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
