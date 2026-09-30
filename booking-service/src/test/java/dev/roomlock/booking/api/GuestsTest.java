package dev.roomlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Which guest a token stands for. */
class GuestsTest {

  private static final String ISSUER = "https://issuer.roomlock.test";
  private static final String OTHER_ISSUER = "https://other.roomlock.test";

  @Test
  void shouldUseTheSubjectAsGuestIdWhenItIsAUuid() {
    // given
    UUID subject = UUID.randomUUID();

    // when
    UUID guestId = Guests.idOf(tokenOf(ISSUER, subject.toString()));

    // then
    assertThat(guestId).isEqualTo(subject);
  }

  /** A provider whose subjects are not UUIDs must still work, and the same user stay one guest. */
  @Test
  void shouldMapTheSubjectToAStableGuestIdWhenItIsNotAUuid() {
    // given
    String subject = "auth0|6f2c0a";

    // when
    UUID first = Guests.idOf(tokenOf(ISSUER, subject));
    UUID again = Guests.idOf(tokenOf(ISSUER, subject));

    // then
    assertThat(first).isEqualTo(again);
  }

  /** Two providers that happen to number their users the same way are two sets of guests. */
  @Test
  void shouldMapToDifferentGuestIdsWhenTheSameSubjectComesFromAnotherIssuer() {
    // given
    String subject = "1";

    // when
    UUID here = Guests.idOf(tokenOf(ISSUER, subject));
    UUID there = Guests.idOf(tokenOf(OTHER_ISSUER, subject));

    // then
    assertThat(here).isNotEqualTo(there);
  }

  private static Jwt tokenOf(String issuer, String subject) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .issuer(issuer)
        .subject(subject)
        .issuedAt(Instant.parse("2026-11-01T09:00:00Z"))
        .expiresAt(Instant.parse("2026-11-01T09:05:00Z"))
        .build();
  }
}
