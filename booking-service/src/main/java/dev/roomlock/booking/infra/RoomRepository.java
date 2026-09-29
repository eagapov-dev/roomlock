package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.Room;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, UUID> {

  List<Room> findByHotelId(UUID hotelId);

  /**
   * Inserts the room unless its hotel already has a room with the same number.
   *
   * @return 1 if inserted, 0 if the number is taken in the hotel
   */
  default int insertIfNumberFree(Room room) {
    return insertUnlessNumberTaken(
        room.getId(),
        room.getHotelId(),
        room.getNumber(),
        room.getCapacity(),
        room.getPricePerNight());
  }

  /**
   * One statement instead of a check and an insert: the unique index decides, so two concurrent
   * requests with the same number cannot both succeed. Version starts at 0, as the entity does.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO room (id, hotel_id, number, capacity, price_per_night, version)
          VALUES (:id, :hotelId, :number, :capacity, :pricePerNight, 0)
          ON CONFLICT (hotel_id, number) DO NOTHING
          """,
      nativeQuery = true)
  int insertUnlessNumberTaken(
      @Param("id") UUID id,
      @Param("hotelId") UUID hotelId,
      @Param("number") String number,
      @Param("capacity") int capacity,
      @Param("pricePerNight") BigDecimal pricePerNight);
}
