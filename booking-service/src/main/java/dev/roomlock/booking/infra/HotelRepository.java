package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.Hotel;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, UUID> {}
