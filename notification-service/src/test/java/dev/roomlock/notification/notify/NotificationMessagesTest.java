package dev.roomlock.notification.notify;

import static dev.roomlock.notification.support.TestData.aBookingCancelled;
import static dev.roomlock.notification.support.TestData.aBookingConfirmed;
import static dev.roomlock.notification.support.TestData.aBookingHeld;
import static dev.roomlock.notification.support.TestData.aRefundRequested;
import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.contract.v1.BookingCancelled;
import dev.roomlock.contract.v1.BookingConfirmed;
import dev.roomlock.contract.v1.BookingHeld;
import dev.roomlock.contract.v1.CancellationReason;
import dev.roomlock.notification.domain.GuestNotification;
import dev.roomlock.notification.domain.NotificationKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The text a guest reads, one case per event of the contract. */
class NotificationMessagesTest {

  private final NotificationMessages messages = new NotificationMessages();

  @Test
  void shouldNameTheDeadlineWhenBookingWasHeld() {
    // given
    BookingHeld held = aBookingHeld().build();

    // when
    GuestNotification notification = messages.render(held).orElseThrow();

    // then
    assertThat(notification.bookingId()).isEqualTo(held.bookingId());
    assertThat(notification.guestId()).isEqualTo(held.guestId());
    assertThat(notification.kind()).isEqualTo(NotificationKind.HOLD);
    assertThat(notification.subject()).isEqualTo("Your room is held");
    assertThat(notification.body())
        .contains(held.bookingId().toString())
        .contains("held until 2026-11-01 09:10 UTC");
  }

  @Test
  void shouldNameTheStayAndThePriceWhenBookingWasConfirmed() {
    // given
    BookingConfirmed confirmed = aBookingConfirmed().build();

    // when
    GuestNotification notification = messages.render(confirmed).orElseThrow();

    // then
    assertThat(notification.kind()).isEqualTo(NotificationKind.CONFIRMATION);
    assertThat(notification.subject()).isEqualTo("Your booking is confirmed");
    assertThat(notification.body())
        .contains("Check in on 2026-11-10, check out on 2026-11-13")
        .contains("450.00 EUR");
  }

  @ParameterizedTest
  @EnumSource(CancellationReason.class)
  void shouldExplainEveryReasonWhenBookingWasCancelled(CancellationReason reason) {
    // given
    BookingCancelled cancelled = aBookingCancelled().withReason(reason).build();

    // when
    GuestNotification notification = messages.render(cancelled).orElseThrow();

    // then
    assertThat(notification.kind()).isEqualTo(NotificationKind.CANCELLATION);
    assertThat(notification.subject()).isEqualTo("Your booking was cancelled");
    assertThat(notification.body()).doesNotContain(reason.name());
    assertThat(notification.body()).endsWith(".");
  }

  @Test
  void shouldRenderNothingWhenEventIsARefundRequest() {
    // given
    var refund = aRefundRequested().build();

    // when
    Optional<GuestNotification> notification = messages.render(refund);

    // then
    assertThat(notification).isEmpty();
  }
}
