package dev.roomlock.notification.notify;

import static dev.roomlock.notification.support.TestData.aBookingHeld;
import static dev.roomlock.notification.support.TestData.aRefundRequested;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import dev.roomlock.contract.v1.BookingEvent;
import dev.roomlock.notification.infra.NotificationRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What happens to an event two deliveries handle at the same moment. The recorded delivery is the
 * deduplication key, so the loser of that race has nothing left to do - and must not be retried or
 * parked as a failure, which is what an escaping violation would cause (rule K-3).
 *
 * <p>The violation is scripted: a race cannot be provoked on demand, and the translation is what
 * needs pinning.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  private static final String UNIQUE_VIOLATION = "23505";
  private static final String CHECK_VIOLATION = "23514";

  @Mock private NotificationRepository notifications;
  @Mock private NotificationDelivery delivery;

  @Test
  void shouldSkipTheEventWhenAnotherDeliveryClaimedItFirst() {
    // given
    BookingEvent event = aBookingHeld().build();
    given(notifications.existsByEventId(event.eventId())).willReturn(false);
    given(delivery.deliver(any(), any())).willThrow(violation(UNIQUE_VIOLATION));

    // when
    // then the redelivery is not a failure: nothing is retried and nothing is parked
    assertThatCode(() -> service().notifyGuest(event)).doesNotThrowAnyException();
  }

  @Test
  void shouldFailWhenTheViolationIsNotADuplicateOfTheEvent() {
    // given
    BookingEvent event = aBookingHeld().build();
    given(notifications.existsByEventId(event.eventId())).willReturn(false);
    given(delivery.deliver(any(), any())).willThrow(violation(CHECK_VIOLATION));

    // when
    // then a row the schema refuses is a real failure of this service
    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(() -> service().notifyGuest(event));
  }

  @Test
  void shouldNotDeliverWhenTheEventWasAlreadyHandled() {
    // given
    BookingEvent event = aBookingHeld().build();
    given(notifications.existsByEventId(event.eventId())).willReturn(true);

    // when
    service().notifyGuest(event);

    // then
    then(delivery).should(never()).deliver(any(), any());
  }

  @Test
  void shouldNotDeliverWhenNoGuestIsToldAboutTheEvent() {
    // given a refund request, which is addressed to the payment service
    BookingEvent event = aRefundRequested().build();

    // when
    service().notifyGuest(event);

    // then
    then(delivery).should(never()).deliver(any(), any());
  }

  private NotificationService service() {
    return new NotificationService(notifications, new NotificationMessages(), delivery);
  }

  private static DataIntegrityViolationException violation(String sqlState) {
    return new DataIntegrityViolationException(
        "could not execute statement", new SQLException("violates a constraint", sqlState));
  }
}
