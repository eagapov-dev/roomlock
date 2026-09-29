package dev.roomlock.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Room entity. {@code version} is a plain column for the optimistic strategy (rule DB-8): it is
 * never written through the entity, only by an explicit native {@code UPDATE}.
 */
@Entity
@Table(name = "room")
public class Room implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "hotel_id", nullable = false, updatable = false)
  private UUID hotelId;

  @Column(name = "number", nullable = false, length = 20)
  private String number;

  @Column(name = "capacity", nullable = false)
  private int capacity;

  @Column(name = "price_per_night", nullable = false, precision = 12, scale = 2)
  private BigDecimal pricePerNight;

  @Column(name = "version", nullable = false, updatable = false)
  private long version;

  @Transient private boolean newEntity;

  protected Room() {}

  private Room(UUID id, UUID hotelId, String number, int capacity, BigDecimal pricePerNight) {
    this.id = id;
    this.hotelId = Objects.requireNonNull(hotelId, "hotelId");
    this.number = Objects.requireNonNull(number, "number");
    this.capacity = capacity;
    this.pricePerNight = Objects.requireNonNull(pricePerNight, "pricePerNight");
    this.version = 0;
    this.newEntity = true;
  }

  /** Creates a new room of the hotel with an application-generated id and version 0. */
  public static Room create(UUID hotelId, String number, int capacity, BigDecimal pricePerNight) {
    return new Room(UUID.randomUUID(), hotelId, number, capacity, pricePerNight);
  }

  @Override
  public UUID getId() {
    return id;
  }

  public UUID getHotelId() {
    return hotelId;
  }

  public String getNumber() {
    return number;
  }

  public int getCapacity() {
    return capacity;
  }

  public BigDecimal getPricePerNight() {
    return pricePerNight;
  }

  public long getVersion() {
    return version;
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
    return this == other || (other instanceof Room room && id.equals(room.getId()));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
