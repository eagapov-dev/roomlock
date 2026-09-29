package dev.roomlock.booking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Hotel entity; skeleton, the mapping comes in phase B. */
@Entity
@Table(name = "hotel")
public class Hotel implements Persistable<UUID> {

  @Id private UUID id;

  protected Hotel() {}

  /** Creates a new hotel with an application-generated id. */
  public static Hotel create(String name, String city) {
    throw new UnsupportedOperationException("Hotel.create is not implemented yet");
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
