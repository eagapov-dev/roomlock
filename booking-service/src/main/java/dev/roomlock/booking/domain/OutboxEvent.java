package dev.roomlock.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

/**
 * A row of the transactional outbox: an event written in the same transaction as the booking it is
 * about, published afterwards. {@code published_at} and {@code attempts} never change through the
 * entity (rule DB-5): the publisher moves them with conditional native UPDATEs.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent implements Persistable<UUID> {

  @Id private UUID id;

  @Column(name = "aggregate_id", nullable = false, updatable = false)
  private UUID aggregateId;

  @Column(name = "type", nullable = false, updatable = false, length = 32)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Nullable
  @Column(name = "published_at", updatable = false)
  private Instant publishedAt;

  @Column(name = "attempts", nullable = false, updatable = false)
  private int attempts;

  @Transient private boolean newEntity;

  protected OutboxEvent() {}

  private OutboxEvent(UUID aggregateId, String type, String payload, Instant now) {
    this.id = UUID.randomUUID();
    this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
    this.type = Objects.requireNonNull(type, "type");
    this.payload = Objects.requireNonNull(payload, "payload");
    this.createdAt = Objects.requireNonNull(now, "now");
    this.attempts = 0;
    this.newEntity = true;
  }

  /**
   * An event waiting to be published.
   *
   * @param aggregateId the booking the event is about, and the key it is published under
   * @param type the type of the contract, as {@code outbox_event_type_valid} spells it
   * @param payload the event as JSON
   */
  public static OutboxEvent pending(UUID aggregateId, String type, String payload, Instant now) {
    return new OutboxEvent(aggregateId, type, payload, now);
  }

  @Override
  public UUID getId() {
    return id;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getType() {
    return type;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Optional<Instant> getPublishedAt() {
    return Optional.ofNullable(publishedAt);
  }

  public int getAttempts() {
    return attempts;
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
    return this == other || (other instanceof OutboxEvent event && id.equals(event.getId()));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
