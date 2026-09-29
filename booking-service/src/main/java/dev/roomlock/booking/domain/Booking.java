package dev.roomlock.booking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Booking entity; skeleton, the mapping comes in phase B. */
@Entity
@Table(name = "booking")
public class Booking implements Persistable<UUID> {

  @Id private UUID id;

  protected Booking() {}

  /** Creates a new HELD booking with an application-generated id. */
  public static Booking hold(
      UUID roomId,
      UUID guestId,
      DateRange stay,
      BigDecimal totalPrice,
      String idempotencyKey,
      String requestHash,
      Instant holdExpiresAt,
      Instant now) {
    throw new UnsupportedOperationException("Booking.hold is not implemented yet");
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
