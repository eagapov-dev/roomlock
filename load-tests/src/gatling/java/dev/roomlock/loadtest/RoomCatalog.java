package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The fixture of a run, created over HTTP so a simulation needs nothing in the database: one hotel
 * and a pool of rooms. The ids of the created rooms stay here and reach the load phase through
 * feeders, which are polled lazily and therefore only after the warmup has filled the pool.
 */
final class RoomCatalog {

  /** Stays are far in the future, so they never collide with data of another tool. */
  private static final LocalDate FIRST_NIGHT = LocalDate.of(2030, 1, 1);

  private final int size;
  private final List<String> roomIds = new CopyOnWriteArrayList<>();
  private final AtomicLong freeStays = new AtomicLong();

  RoomCatalog(int size) {
    this.size = size;
  }

  /**
   * Warmup: a single user creates the hotel and {@code size} rooms. Run it before the load phase
   * with {@code andThen}, the room ids are only there afterwards.
   */
  ScenarioBuilder warmup() {
    return scenario("setup: one hotel and " + size + " rooms" + LoadTestSettings.label())
        .exec(
            http("create hotel")
                .post("/api/v1/hotels")
                .body(StringBody("{\"name\":\"RoomLock load test\",\"city\":\"Loadville\"}"))
                .check(status().is(201))
                .check(jsonPath("$.id").saveAs("hotelId")))
        .repeat(size, "roomIndex")
        .on(
            exec(http("add room")
                    .post("/api/v1/hotels/#{hotelId}/rooms")
                    .body(
                        StringBody(
                            session ->
                                "{\"number\":\"L"
                                    + session.getInt("roomIndex")
                                    + "\",\"capacity\":2,\"pricePerNight\":120.00}"))
                    .check(status().is(201))
                    .check(jsonPath("$.id").saveAs("createdRoomId")))
                .exec(
                    session -> {
                      roomIds.add(session.getString("createdRoomId"));
                      return session;
                    }));
  }

  /** Every virtual user asks for the very same room and the very same night: maximum contention. */
  Iterator<Map<String, Object>> contendedStays() {
    return Stream.generate(() -> stay(roomIds.get(0), FIRST_NIGHT)).iterator();
  }

  /**
   * A (room, night) pair no other virtual user of this run gets: the conflict-free path. Rooms are
   * cycled first, then nights, so a run of any size never overlaps itself.
   */
  Iterator<Map<String, Object>> freeStays() {
    return Stream.generate(this::nextFreeStay).iterator();
  }

  private Map<String, Object> nextFreeStay() {
    long slot = freeStays.getAndIncrement();
    int rooms = roomIds.size();
    return stay(roomIds.get((int) (slot % rooms)), FIRST_NIGHT.plusDays(slot / rooms));
  }

  /** Session attributes of one hold request: a fresh guest and a fresh idempotency key. */
  private static Map<String, Object> stay(String roomId, LocalDate checkIn) {
    return Map.of(
        "roomId", roomId,
        "guestId", UUID.randomUUID().toString(),
        "idempotencyKey", UUID.randomUUID().toString(),
        "checkIn", checkIn.toString(),
        "checkOut", checkIn.plusDays(1).toString());
  }
}
