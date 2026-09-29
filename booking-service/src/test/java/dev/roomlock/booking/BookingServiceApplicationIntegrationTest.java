package dev.roomlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.roomlock.booking.support.BookingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@BookingIntegrationTest
class BookingServiceApplicationIntegrationTest {

  @Autowired private TestRestTemplate rest;

  @Test
  void shouldReportHealthUpWhenContextStarts() {
    // given
    String healthPath = "/actuator/health";

    // when
    ResponseEntity<String> response = rest.getForEntity(healthPath, String.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }
}
