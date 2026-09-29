package dev.roomlock.booking.outbox;

import static dev.roomlock.booking.support.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.booking.infra.OutboxEventRepository;
import dev.roomlock.booking.infra.PublisherLease;
import dev.roomlock.booking.support.BookingJpaTest;
import dev.roomlock.booking.support.DatabaseCleaner;
import dev.roomlock.booking.support.MutableClock;
import dev.roomlock.booking.support.OutboxTable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Draining the outbox (task 4.2). The broker is a stub: what has to be pinned here is which rows go
 * out, in which order, and what the row looks like afterwards - not the transport.
 */
@BookingJpaTest
class OutboxPublisherTest {

  private static final String BOOKING_HELD = "BookingHeld";
  private static final String BOOKING_CANCELLED = "BookingCancelled";
  private static final int BATCH_SIZE = 2;
  private static final int STUCK_AFTER = 3;
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final OutboxProperties.Retention RETENTION =
      new OutboxProperties.Retention(Duration.ofHours(1), Duration.ofDays(7), 2);
  private static final String PUBLISHED_COUNTER = "roomlock.outbox.published";
  private static final String FAILED_COUNTER = "roomlock.outbox.failures";
  private static final String BACKLOG_GAUGE = "roomlock.outbox.unpublished";
  private static final String STUCK_GAUGE = "roomlock.outbox.stuck";

  @Autowired private OutboxEventRepository events;
  // A plain component rather than a repository, so the JPA slice does not create it for us.
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OutboxTable outbox;
  @Autowired private MutableClock clock;
  @Autowired private DatabaseCleaner databaseCleaner;

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private PublisherLease lease;
  private final RecordingSender sender = new RecordingSender();

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    lease = new PublisherLease(jdbc);
    clock.setInstant(NOW);
  }

  @Test
  void shouldSendPendingEventsOldestFirstWhenBatchIsPublished() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID held = pending(bookingId, BOOKING_HELD, NOW);
    UUID cancelled = pending(bookingId, BOOKING_CANCELLED, NOW.plusSeconds(5));

    // when
    int published = publisher().publishBatch();

    // then
    assertThat(published).isEqualTo(2);
    assertThat(sender.sentIds()).containsExactly(held, cancelled);
    assertThat(outbox.publishedAt(held)).contains(NOW);
    assertThat(outbox.publishedAt(cancelled)).contains(NOW);
  }

  @Test
  void shouldSendNothingWhenEveryEventIsPublished() {
    // given
    outbox.insertPublished(UUID.randomUUID(), BOOKING_HELD, payload(), NOW, NOW.plusSeconds(1));

    // when
    int published = publisher().publishBatch();

    // then
    assertThat(published).isZero();
    assertThat(sender.sentIds()).isEmpty();
  }

  @Test
  void shouldSendAtMostOneBatchWhenMoreEventsAreWaiting() {
    // given three events and a batch of two
    UUID bookingId = UUID.randomUUID();
    pending(bookingId, BOOKING_HELD, NOW);
    pending(bookingId, BOOKING_CANCELLED, NOW.plusSeconds(1));
    UUID last = pending(bookingId, BOOKING_CANCELLED, NOW.plusSeconds(2));

    // when
    int published = publisher().publishBatch();

    // then
    assertThat(published).isEqualTo(BATCH_SIZE);
    assertThat(outbox.publishedAt(last)).isEmpty();
  }

  @Test
  void shouldStopAtTheRefusedEventWhenTheBrokerRefusesOne() {
    // given the events of one booking, the first of which cannot be sent
    UUID bookingId = UUID.randomUUID();
    UUID refused = pending(bookingId, BOOKING_HELD, NOW);
    UUID later = pending(bookingId, BOOKING_CANCELLED, NOW.plusSeconds(1));
    RefusingSender broker = new RefusingSender(refused);

    // when
    int published = publisher(broker).publishBatch();

    // then the order of one booking survives: nothing after the refused event goes out
    assertThat(published).isZero();
    assertThat(outbox.publishedAt(refused)).isEmpty();
    assertThat(outbox.publishedAt(later)).isEmpty();
    assertThat(outbox.attempts(refused)).isEqualTo(1);
    assertThat(outbox.attempts(later)).isZero();
  }

  @Test
  void shouldKeepPublishingOtherBookingsWhenOneEventIsRefused() {
    // given the refused event of one booking, and another booking waiting behind it
    UUID refusedBooking = UUID.randomUUID();
    UUID refused = pending(refusedBooking, BOOKING_HELD, NOW);
    UUID otherBooking = UUID.randomUUID();
    UUID other = pending(otherBooking, BOOKING_HELD, NOW.plusSeconds(1));

    // when
    int published = publisher(new RefusingSender(refused)).publishBatch();

    // then only the booking that cannot be published waits
    assertThat(published).isEqualTo(1);
    assertThat(outbox.publishedAt(other)).contains(NOW);
    assertThat(outbox.publishedAt(refused)).isEmpty();
  }

  @Test
  void shouldHoldBackTheRestOfTheBookingWhenItsEarlierEventIsRefused() {
    // given a hold that cannot be published and the cancellation of the same booking behind it
    UUID bookingId = UUID.randomUUID();
    UUID refused = pending(bookingId, BOOKING_HELD, NOW);
    UUID later = pending(bookingId, BOOKING_CANCELLED, NOW.plusSeconds(1));

    // when
    publisher(new RefusingSender(refused)).publishBatch();

    // then a cancellation never overtakes the hold it cancels
    assertThat(outbox.publishedAt(later)).isEmpty();
  }

  @Test
  void shouldCountAnEventAsStuckWhenTheBrokerKeepsRefusingIt() {
    // given an event that has already been refused as often as the configuration allows
    UUID bookingId = UUID.randomUUID();
    UUID refused = pending(bookingId, BOOKING_HELD, NOW);
    OutboxPublisher publisher = publisher(new RefusingSender(refused));
    for (int sweep = 1; sweep < STUCK_AFTER; sweep++) {
      publisher.publishBatch();
    }
    assertThat(registry.get(STUCK_GAUGE).gauge().value()).isZero();

    // when
    publisher.publishBatch();

    // then it is still kept, and it is visible as stuck rather than as a passing outage
    assertThat(outbox.attempts(refused)).isEqualTo(STUCK_AFTER);
    assertThat(registry.get(STUCK_GAUGE).gauge().value()).isEqualTo(1);
    assertThat(outbox.publishedAt(refused)).isEmpty();
  }

  @Test
  void shouldSendTheRefusedEventWhenTheNextSweepFindsTheBrokerBack() {
    // given a broker that refuses once, and the one publisher of this instance
    UUID event = pending(UUID.randomUUID(), BOOKING_HELD, NOW);
    OutboxPublisher publisher = publisher(new RefusingOnceSender(event));
    assertThat(publisher.publishBatch()).isZero();

    // when
    int published = publisher.publishBatch();

    // then
    assertThat(published).isEqualTo(1);
    assertThat(outbox.publishedAt(event)).contains(NOW);
    assertThat(outbox.attempts(event)).isEqualTo(1);
  }

  @Test
  void shouldCountPublishedFailedAndWaitingEventsWhenBatchIsPublished() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID sent = pending(bookingId, BOOKING_HELD, NOW);
    UUID refused = pending(UUID.randomUUID(), BOOKING_HELD, NOW.plusSeconds(1));
    OutboxPublisher publisher = publisher(new RefusingSender(refused));

    // when
    publisher.publishBatch();

    // then
    assertThat(registry.get(PUBLISHED_COUNTER).counter().count()).isEqualTo(1);
    assertThat(registry.get(FAILED_COUNTER).counter().count()).isEqualTo(1);
    assertThat(registry.get(BACKLOG_GAUGE).gauge().value()).isEqualTo(1);
    assertThat(outbox.publishedAt(sent)).contains(NOW);
  }

  @Test
  void shouldSendNothingWhenAnotherInstanceHoldsTheLease() {
    // given another instance took the lease a moment ago
    assertThat(lease.heldBy(UUID.randomUUID(), NOW, LEASE)).isTrue();
    pending(UUID.randomUUID(), BOOKING_HELD, NOW);

    // when
    int published = publisher().publishBatch();

    // then
    assertThat(published).isZero();
    assertThat(sender.sentIds()).isEmpty();
  }

  @Test
  void shouldTakeOverTheSweepWhenTheOtherLeaseHasExpired() {
    // given a lease nobody renewed
    UUID gone = UUID.randomUUID();
    assertThat(lease.heldBy(gone, NOW.minus(LEASE).minusSeconds(1), LEASE)).isTrue();
    UUID event = pending(UUID.randomUUID(), BOOKING_HELD, NOW);

    // when
    int published = publisher().publishBatch();

    // then
    assertThat(published).isEqualTo(1);
    assertThat(outbox.publishedAt(event)).contains(NOW);
  }

  @Test
  void shouldKeepSweepingWhenTheSameInstanceRenewsItsLease() {
    // given
    OutboxPublisher publisher = publisher();
    pending(UUID.randomUUID(), BOOKING_HELD, NOW);
    assertThat(publisher.publishBatch()).isEqualTo(1);
    pending(UUID.randomUUID(), BOOKING_CANCELLED, NOW.plusSeconds(1));

    // when the next sweep of the same instance runs, well inside its own lease
    int published = publisher.publishBatch();

    // then
    assertThat(published).isEqualTo(1);
  }

  @Test
  void shouldDeleteOnlyEventsThatWentOutLongAgoWhenPruning() {
    // given
    UUID old =
        outbox.insertPublished(
            UUID.randomUUID(),
            BOOKING_HELD,
            payload(),
            NOW.minus(Duration.ofDays(30)),
            NOW.minus(Duration.ofDays(30)));
    UUID recent =
        outbox.insertPublished(
            UUID.randomUUID(), BOOKING_HELD, payload(), NOW.minusSeconds(60), NOW.minusSeconds(30));
    UUID waiting = pending(UUID.randomUUID(), BOOKING_HELD, NOW.minus(Duration.ofDays(30)));

    // when
    int pruned = publisher().prunePublished();

    // then an event still waiting is never pruned, whatever its age
    assertThat(pruned).isEqualTo(1);
    assertThat(events.findById(old)).isEmpty();
    assertThat(events.findById(recent)).isPresent();
    assertThat(events.findById(waiting)).isPresent();
  }

  @Test
  void shouldPruneAtMostOneBatchWhenManyEventsAreOldEnough() {
    // given three prunable events and a retention batch of two
    for (int event = 0; event < 3; event++) {
      outbox.insertPublished(
          UUID.randomUUID(),
          BOOKING_HELD,
          payload(),
          NOW.minus(Duration.ofDays(30)),
          NOW.minus(Duration.ofDays(30)));
    }

    // when
    int pruned = publisher().prunePublished();

    // then
    assertThat(pruned).isEqualTo(RETENTION.batchSize());
    assertThat(events.count()).isEqualTo(1);
  }

  private UUID pending(UUID aggregateId, String type, java.time.Instant createdAt) {
    return outbox.insertPending(aggregateId, type, payload(), createdAt);
  }

  private static String payload() {
    return "{\"eventId\":\"" + UUID.randomUUID() + "\"}";
  }

  private static OutboxProperties properties() {
    return new OutboxProperties(Duration.ofSeconds(1), BATCH_SIZE, STUCK_AFTER, LEASE, RETENTION);
  }

  private OutboxPublisher publisher() {
    return publisher(sender);
  }

  private OutboxPublisher publisher(EventSender broker) {
    return new OutboxPublisher(
        events,
        lease,
        broker,
        properties(),
        clock,
        new OutboxMetrics(registry, events, properties()));
  }

  /** A broker that takes everything and remembers what it took. */
  private static final class RecordingSender implements EventSender {

    private final List<UUID> sent = new ArrayList<>();

    @Override
    public void send(OutboxEvent event) {
      sent.add(event.getId());
    }

    List<UUID> sentIds() {
      return List.copyOf(sent);
    }
  }

  /** A broker that refuses one event the first time it is offered, and takes it afterwards. */
  private static final class RefusingOnceSender implements EventSender {

    private final UUID refusedOnce;
    private boolean refused;

    RefusingOnceSender(UUID refusedOnce) {
      this.refusedOnce = refusedOnce;
    }

    @Override
    public void send(OutboxEvent event) {
      if (!refused && event.getId().equals(refusedOnce)) {
        refused = true;
        throw new EventSendFailedException(
            "broker is unreachable", new IllegalStateException("no connection"));
      }
    }
  }

  /** A broker that refuses one event and takes the rest. */
  private static final class RefusingSender implements EventSender {

    private final UUID refused;

    RefusingSender(UUID refused) {
      this.refused = refused;
    }

    @Override
    public void send(OutboxEvent event) {
      if (event.getId().equals(refused)) {
        throw new EventSendFailedException(
            "broker is unreachable", new IllegalStateException("no connection"));
      }
    }
  }
}
