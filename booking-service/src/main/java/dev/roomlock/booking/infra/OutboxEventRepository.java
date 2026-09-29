package dev.roomlock.booking.infra;

import dev.roomlock.booking.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /** The oldest events nobody has published yet, driven by {@code outbox_event_unpublished_idx}. */
  List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAt(Limit limit);

  /** How many events are still waiting, the backlog a dashboard watches. */
  long countByPublishedAtIsNull();

  /**
   * How many waiting events have been refused at least this often, which is no longer an outage.
   */
  long countByPublishedAtIsNullAndAttemptsGreaterThanEqual(int attempts);

  /**
   * Marks the event published (rule DB-5). The {@code IS NULL} is the transition itself: of two
   * publishers that sent the same event, only one marks it, and the other learns it was a
   * duplicate.
   *
   * @return 1 if this call marked it, 0 if it was already marked
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          UPDATE outbox_event
          SET published_at = CAST(:publishedAt AS timestamptz)
          WHERE id = :eventId AND published_at IS NULL
          """,
      nativeQuery = true)
  int markPublished(@Param("eventId") UUID eventId, @Param("publishedAt") Instant publishedAt);

  /**
   * Deletes up to {@code batchSize} events that went out before {@code before}, oldest first.
   *
   * <p>An outbox is a queue, not a history: the event is on the topic, and keeping the row forever
   * grows the table the unpublished index and the backlog gauge live in.
   *
   * @return how many rows were deleted, so a caller can tell a finished sweep from a full batch
   */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          DELETE FROM outbox_event
          WHERE id IN (
            SELECT id FROM outbox_event
            WHERE published_at IS NOT NULL AND published_at < CAST(:before AS timestamptz)
            ORDER BY published_at
            LIMIT :batchSize
          )
          """,
      nativeQuery = true)
  int deletePublishedBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);

  /** Counts a publish that failed, so a stuck event can be told from a fresh one (rule DB-5). */
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      value = "UPDATE outbox_event SET attempts = attempts + 1 WHERE id = :eventId",
      nativeQuery = true)
  int recordAttempt(@Param("eventId") UUID eventId);
}
