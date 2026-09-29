package dev.roomlock.notification.infra;

import static dev.roomlock.notification.support.TestData.NOW;
import static dev.roomlock.notification.support.TestData.aBookingCancelled;
import static dev.roomlock.notification.support.TestData.aBookingConfirmed;
import static dev.roomlock.notification.support.TestData.aBookingHeld;
import static dev.roomlock.notification.support.TestData.aRefundRequested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
import dev.roomlock.contract.v1.RefundRequested;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.NotificationKind;
import dev.roomlock.notification.support.BookingEventPublisher;
import dev.roomlock.notification.support.DatabaseCleaner;
import dev.roomlock.notification.support.NotificationIntegrationTest;
import dev.roomlock.notification.support.NotificationTable;
import dev.roomlock.notification.support.NotificationTable.Row;
import dev.roomlock.notification.support.RecordingNotificationSender;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The consumer of {@code roomlock.booking-events.v1}: every event kind becomes one recorded and
 * sent notification, a redelivery none, and a record the consumer cannot read none either (task 5).
 *
 * <p>Waiting on the broker is {@link org.awaitility.Awaitility}, never a sleep (rule T-7). Where a
 * test has to know that a record was already consumed, it publishes a marker event under the same
 * booking id: the same key means the same partition, so the marker is handled last.
 */
@NotificationIntegrationTest
class BookingEventsListenerIntegrationTest {

  private static final Duration CONSUMED = Duration.ofSeconds(30);

  @Autowired private BookingEventPublisher events;
  @Autowired private NotificationTable notifications;
  @Autowired private RecordingNotificationSender sender;
  @Autowired private DatabaseCleaner databaseCleaner;

  @BeforeEach
  void setUp() {
    databaseCleaner.truncateAll();
    sender.clear();
  }

  @Test
  void shouldTellTheGuestUntilWhenTheRoomIsHeldWhenBookingWasHeld() {
    // given
    BookingHeld held = aBookingHeld().build();

    // when
    events.publish(held);

    // then
    Row recorded = awaitRecorded(held.eventId());
    assertThat(recorded.eventType()).isEqualTo("BookingHeld");
    assertThat(recorded.bookingId()).isEqualTo(held.bookingId());
    assertThat(recorded.guestId()).isEqualTo(held.guestId());
    assertThat(recorded.kind()).isEqualTo(NotificationKind.HOLD.name());
    assertThat(recorded.channel()).isEqualTo("LOG");
    assertThat(recorded.subject()).isEqualTo("Your room is held");
    assertThat(recorded.body()).contains("held until 2026-11-01 09:10 UTC");
    assertThat(recorded.occurredAt()).isEqualTo(NOW);
    assertThat(recorded.sentAt()).isEqualTo(NOW);
    assertThat(sender.sentFor(held.bookingId()))
        .singleElement()
        .extracting(GuestNotification::kind)
        .isEqualTo(NotificationKind.HOLD);
  }

  @Test
  void shouldTellTheGuestTheStayIsPaidForWhenBookingWasConfirmed() {
    // given
    BookingConfirmed confirmed = aBookingConfirmed().build();

    // when
    events.publish(confirmed);

    // then
    Row recorded = awaitRecorded(confirmed.eventId());
    assertThat(recorded.eventType()).isEqualTo("BookingConfirmed");
    assertThat(recorded.kind()).isEqualTo(NotificationKind.CONFIRMATION.name());
    assertThat(recorded.subject()).isEqualTo("Your booking is confirmed");
    assertThat(recorded.body()).contains("Check in on 2026-11-10, check out on 2026-11-13");
    assertThat(recorded.body()).contains("450.00 EUR");
    assertThat(recorded.sentAt()).isEqualTo(NOW);
    assertThat(sender.sentFor(confirmed.bookingId())).hasSize(1);
  }

  @ParameterizedTest
  @CsvSource({
    "USER_CANCELLED, you cancelled it",
    "HOLD_EXPIRED, the hold on the room expired before the stay was paid for",
    "PAYMENT_DECLINED, the payment was declined"
  })
  void shouldNameTheReasonWhenBookingWasCancelled(CancellationReason reason, String explanation) {
    // given
    BookingCancelled cancelled = aBookingCancelled().withReason(reason).build();

    // when
    events.publish(cancelled);

    // then
    Row recorded = awaitRecorded(cancelled.eventId());
    assertThat(recorded.eventType()).isEqualTo("BookingCancelled");
    assertThat(recorded.kind()).isEqualTo(NotificationKind.CANCELLATION.name());
    assertThat(recorded.subject()).isEqualTo("Your booking was cancelled");
    assertThat(recorded.body()).contains(explanation);
    assertThat(recorded.sentAt()).isEqualTo(NOW);
    assertThat(sender.sentFor(cancelled.bookingId())).hasSize(1);
  }

  @Test
  void shouldNotifyOnceWhenTheSameEventIsDeliveredTwice() {
    // given
    BookingHeld held = aBookingHeld().build();
    BookingConfirmed marker = aBookingConfirmed().withBookingId(held.bookingId()).build();

    // when
    events.publish(held);
    events.publish(held);
    events.publish(marker);

    // then
    awaitRecorded(marker.eventId());
    assertThat(notifications.countForEvent(held.eventId())).isEqualTo(1);
    assertThat(sender.sentFor(held.bookingId()))
        .extracting(GuestNotification::kind)
        .containsExactly(NotificationKind.HOLD, NotificationKind.CONFIRMATION);
  }

  @Test
  void shouldKeepConsumingWhenPayloadIsMalformed() {
    // given
    BookingHeld held = aBookingHeld().build();
    String key = held.bookingId().toString();

    // when
    events.publishRaw(key, "{\"eventId\": not json", "BookingHeld");
    events.publish(held);

    // then
    awaitRecorded(held.eventId());
    assertThat(notifications.findByBookingId(held.bookingId())).hasSize(1);
    assertThat(sender.sentFor(held.bookingId())).hasSize(1);
  }

  @Test
  void shouldKeepConsumingWhenEventTypeIsUnknown() {
    // given
    BookingHeld held = aBookingHeld().build();
    String key = held.bookingId().toString();

    // when
    events.publishRaw(key, "{\"eventId\":\"" + UUID.randomUUID() + "\"}", "BookingRescheduled");
    events.publish(held);

    // then
    awaitRecorded(held.eventId());
    assertThat(notifications.findByBookingId(held.bookingId())).hasSize(1);
  }

  @Test
  void shouldKeepConsumingWhenEventTypeHeaderIsMissing() {
    // given
    BookingHeld held = aBookingHeld().build();
    String key = held.bookingId().toString();

    // when
    events.publishRaw(key, "{\"eventId\":\"" + UUID.randomUUID() + "\"}", null);
    events.publish(held);

    // then
    awaitRecorded(held.eventId());
    assertThat(notifications.findByBookingId(held.bookingId())).hasSize(1);
  }

  @Test
  void shouldNotifyNobodyWhenEventIsARefundRequest() {
    // given
    RefundRequested refund = aRefundRequested().build();
    BookingHeld marker = aBookingHeld().withBookingId(refund.bookingId()).build();

    // when
    events.publish(refund);
    events.publish(marker);

    // then
    awaitRecorded(marker.eventId());
    assertThat(notifications.countForEvent(refund.eventId())).isZero();
    assertThat(sender.sentFor(refund.bookingId())).hasSize(1);
  }

  private Row awaitRecorded(UUID eventId) {
    await()
        .atMost(CONSUMED)
        .untilAsserted(() -> assertThat(notifications.findByEventId(eventId)).isPresent());
    return notifications.findByEventId(eventId).orElseThrow();
  }
}
