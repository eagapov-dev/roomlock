package dev.roomlock.booking.outbox;

import static dev.roomlock.booking.support.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.domain.OutboxEvent;
import dev.roomlock.booking.infra.OutboxEventRepository;
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

/**
 * Draining the outbox (task 4.2). The broker is a stub: what has to be pinned here is which rows go
 * out, in which order, and what the row looks like afterwards - not the transport.
 */
@BookingJpaTest
class OutboxPublisherTest {

  private static final String BOOKING_HELD = "BookingHeld";
  private static final String BOOKING_CANCELLED = "BookingCancelled";
  private static final int BATCH_SIZE = 2;
  private static final String PUBLISHED_COUNTER = "roomlock.outbox.published";
  private static final String FAILED_COUNTER = "roomlock.outbox.failures";
  private static final String BACKLOG_GAUGE = "roomlock.outbox.unpublished";

  @Autowired private OutboxEventRepository events;
  @Autowired private OutboxTable outbox;
  @Autowired private MutableClock clock;
  @Autowired private DatabaseCleaner databaseCleaner;

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final RecordingSender sender = new RecordingSender();

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
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
  void shouldSendTheRefusedEventWhenTheNextSweepFindsTheBrokerBack() {
    // given
    UUID bookingId = UUID.randomUUID();
    UUID event = pending(bookingId, BOOKING_HELD, NOW);
    publisher(new RefusingSender(event)).publishBatch();

    // when
    int published = publisher().publishBatch();

    // then
    assertThat(published).isEqualTo(1);
    assertThat(sender.sentIds()).containsExactly(event);
    assertThat(outbox.publishedAt(event)).contains(NOW);
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

  private UUID pending(UUID aggregateId, String type, java.time.Instant createdAt) {
    return outbox.insertPending(aggregateId, type, payload(), createdAt);
  }

  private static String payload() {
    return "{\"eventId\":\"" + UUID.randomUUID() + "\"}";
  }

  private OutboxPublisher publisher() {
    return publisher(sender);
  }

  private OutboxPublisher publisher(EventSender broker) {
    return new OutboxPublisher(
        events,
        broker,
        new OutboxProperties(Duration.ofSeconds(1), BATCH_SIZE),
        clock,
        new OutboxMetrics(registry, events));
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
