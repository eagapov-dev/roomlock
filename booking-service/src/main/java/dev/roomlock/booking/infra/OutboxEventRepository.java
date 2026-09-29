package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /** The oldest events nobody has published yet, driven by {@code outbox_event_unpublished_idx}. */
  List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAt(Limit limit);

  /** How many events are still waiting, the backlog a dashboard watches. */
  long countByPublishedAtIsNull();

  /**
   * Marks the event published unless another publisher did it first.
   *
   * @return 1 if this call marked it, 0 if it was already marked
   */
  default int markPublished(UUID eventId, Instant publishedAt) {
    throw new UnsupportedOperationException("Not implemented yet");
  }

  /** Counts a publish that failed, so a stuck event can be told from a fresh one. */
  default int recordAttempt(UUID eventId) {
    throw new UnsupportedOperationException("Not implemented yet");
  }
}
