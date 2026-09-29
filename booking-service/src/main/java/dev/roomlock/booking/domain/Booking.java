package dev.roomlock.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

/**
 * Booking entity. The status never changes through the entity (rule DB-5): every transition is a
 * conditional native {@code UPDATE}. The generated {@code stay} column is not mapped (D5, DB-7).
 */
@Entity
@Table(name = "booking")
public class Booking implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "room_id", nullable = false, updatable = false)
  private UUID roomId;

  @Column(name = "guest_id", nullable = false, updatable = false)
  private UUID guestId;

  @Column(name = "check_in", nullable = false, updatable = false)
  private LocalDate checkIn;

  @Column(name = "check_out", nullable = false, updatable = false)
  private LocalDate checkOut;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private BookingStatus status;

  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "cancel_reason", length = 32)
  private CancelReason cancelReason;

  @Column(name = "hold_expires_at", nullable = false)
  private Instant holdExpiresAt;

  @Nullable
  @Column(name = "payment_started_at")
  private Instant paymentStartedAt;

  @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
  private BigDecimal totalPrice;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
  private String requestHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Transient private boolean newEntity;

  protected Booking() {}

  private Booking(
      UUID roomId,
      UUID guestId,
      DateRange stay,
      BigDecimal totalPrice,
      String idempotencyKey,
      String requestHash,
      Instant holdExpiresAt,
      Instant now) {
    this.id = UUID.randomUUID();
    this.roomId = Objects.requireNonNull(roomId, "roomId");
    this.guestId = Objects.requireNonNull(guestId, "guestId");
    this.checkIn = stay.checkIn();
    this.checkOut = stay.checkOut();
    this.status = BookingStatus.HELD;
    this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
    this.totalPrice = Objects.requireNonNull(totalPrice, "totalPrice");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
    this.newEntity = true;
  }

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
    return new Booking(
        roomId, guestId, stay, totalPrice, idempotencyKey, requestHash, holdExpiresAt, now);
  }

  @Override
  public UUID getId() {
    return id;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public UUID getGuestId() {
    return guestId;
  }

  public DateRange getStay() {
    return new DateRange(checkIn, checkOut);
  }

  public BookingStatus getStatus() {
    return status;
  }

  public Optional<CancelReason> getCancelReason() {
    return Optional.ofNullable(cancelReason);
  }

  public Instant getHoldExpiresAt() {
    return holdExpiresAt;
  }

  public Optional<Instant> getPaymentStartedAt() {
    return Optional.ofNullable(paymentStartedAt);
  }

  public BigDecimal getTotalPrice() {
    return totalPrice;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
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
    return this == other || (other instanceof Booking booking && id.equals(booking.getId()));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
