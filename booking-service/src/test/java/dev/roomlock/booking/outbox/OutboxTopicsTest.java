package dev.roomlock.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.support.BookingIntegrationTest;
import dev.roomlock.contract.v1.Topics;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The producer declares the topics of the contract, so the first event of a topic does not wait for
 * the broker to create it lazily - a wait of up to {@code max.block.ms} that holds up the sweep,
 * and a refusal for good on a broker with automatic creation switched off.
 */
@BookingIntegrationTest
class OutboxTopicsTest {

  @Autowired private Map<String, NewTopic> topics;

  @Test
  void shouldDeclareEveryTopicTheOutboxPublishesTo() {
    // given
    // when
    // then
    assertThat(topics.values())
        .extracting(NewTopic::name)
        .contains(Topics.BOOKING_EVENTS, Topics.REFUND_REQUESTS);
  }
}
