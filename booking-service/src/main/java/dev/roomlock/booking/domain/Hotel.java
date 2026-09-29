package dev.roomlock.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "hotel")
public class Hotel implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "city", nullable = false, length = 100)
  private String city;

  @Transient private boolean newEntity;

  protected Hotel() {}

  private Hotel(UUID id, String name, String city) {
    this.id = id;
    this.name = Objects.requireNonNull(name, "name");
    this.city = Objects.requireNonNull(city, "city");
    this.newEntity = true;
  }

  /** Creates a new hotel with an application-generated id. */
  public static Hotel create(String name, String city) {
    return new Hotel(UUID.randomUUID(), name, city);
  }

  @Override
  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getCity() {
    return city;
  }

  @Override
  public boolean isNew() {
    return newEntity;
  }

  @PostPersist
  @PostLoad
  void markNotNew() {
    newEntity = false;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || (other instanceof Hotel hotel && id.equals(hotel.getId()));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
