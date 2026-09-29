package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.Room;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, UUID> {

  List<Room> findByHotelId(UUID hotelId);
}
