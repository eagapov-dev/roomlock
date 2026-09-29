package dev.roomlock.booking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Room entity; skeleton, the mapping comes in phase B. */
@Entity
@Table(name = "room")
public class Room implements Persistable<UUID> {

  @Id private UUID id;

  protected Room() {}

  /** Creates a new room of the hotel with an application-generated id and version 0. */
  public static Room create(UUID hotelId, String number, int capacity, BigDecimal pricePerNight) {
    throw new UnsupportedOperationException("Room.create is not implemented yet");
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
