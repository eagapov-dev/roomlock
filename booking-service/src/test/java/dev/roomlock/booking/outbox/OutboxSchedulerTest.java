package dev.roomlock.booking.outbox;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The trigger only hands the sweep over; what it publishes is the publisher's business. */
@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

  @Mock private OutboxPublisher publisher;

  @Test
  void shouldPublishOneBatchWhenTriggerFires() {
    // given
    given(publisher.publishBatch()).willReturn(3);

    // when
    new OutboxScheduler(publisher).publish();

    // then
    then(publisher).should().publishBatch();
  }
}
