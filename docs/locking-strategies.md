# Locking strategies

## The problem

One room, two guests, overlapping stays. The rule is that at most one booking may hold a given room
over any given night, where a stay is the half-open range `[check_in, check_out)` — the check-out day
is not a night of the stay (decision D5, `dev.roomlock.booking.domain.DateRange`). A booking holds its
room while its status is `HELD`, `PAYING` or `CONFIRMED`; `CANCELLED` releases it.

Naively that is a read followed by a write: ask whether the room is free, then insert. Between the
two, another request can insert. RoomLock implements three different ways to close that window, keeps
all three in the codebase at once, and makes them comparable.

`roomlock.locking.strategy` picks one. It is `pessimistic`, `optimistic` or `constraint`, and it
defaults to `constraint` (`booking-service/src/main/resources/application.yml`, decision D2).

## The shared frame

All three implement `HoldStrategy` and extend
`booking-service/src/main/java/dev/roomlock/booking/hold/TransactionalHoldStrategy.java`, which owns
everything that is not the strategy itself:

- **One transaction per attempt**, opened with a `TransactionTemplate` rather than `@Transactional`.
  Two reasons, both in the class javadoc: a strategy that retries needs the lost attempt rolled back
  before the next one starts, and a constraint violation has to be translated *after* the rollback,
  never inside the transaction that broke.
- **The attempt loop.** `hold` runs up to `maxAttempts` attempts and stops as soon as the outcome is
  not `Contended`. `Contended` is the only retryable outcome; `Busy`, `KeyTaken` and `Inserted` are
  final.
- **Translating a violation into an outcome.** `SqlState` reads the PostgreSQL error code out of the
  exception chain: `23P01` (`booking_no_overlap`) becomes `Busy`, `23505`
  (`booking_guest_id_idempotency_key_key`) becomes `KeyTaken`. Anything else is rethrown.
- **Metrics.** Every attempt is timed, every attempt past the first counts as a retry, and the
  answer the caller gets is counted once — see [what the metrics show](#what-the-metrics-show).

Each subclass therefore only implements `write(HoldAttempt)`: what one attempt does. Every one of
them ends the same way when it succeeds — `bookings.saveAndFlush(...)` followed by
`outbox.recordHeld(booking)`, inside the attempt's transaction, so the booking and its `BookingHeld`
event commit together (rule K-1).

`HoldStrategies` collects every `HoldStrategy` bean into an `EnumMap` and exposes `active()` for the
configured one; all three beans exist in every deployment, which is what lets the concurrency tests
parameterise over `LockingStrategy` without restarting the context.

## One attempt of each

### Pessimistic — `PessimisticHoldStrategy`

```
RoomRepository.lockById   ->  SELECT id FROM room WHERE id = :roomId FOR UPDATE
BookingRepository.existsActiveOverlap  ->  does an active booking of this room touch the stay?
INSERT booking (HELD) + INSERT outbox_event
```

The row lock is taken before the question is asked, so holds of one room run strictly one after
another and the overlap check cannot go stale between the check and the insert. `ATTEMPTS = 1`: there
is nothing to retry, because a conflicting request is still waiting on the lock rather than racing.

Note that the lock is on `room`, not on the booking rows — there is no booking row to lock yet. That
is the point: the room row is the serialisation point for the whole room.

### Optimistic — `OptimisticHoldStrategy`

```
RoomRepository.findVersionById  ->  SELECT version FROM room WHERE id = :roomId   (no lock)
BookingRepository.existsActiveOverlap
RoomRepository.bumpVersion     ->  UPDATE room SET version = version + 1
                                   WHERE id = :roomId AND version = :version
  0 rows -> Contended, roll back, try again
INSERT booking (HELD) + INSERT outbox_event
```

Nothing is locked up front. The version read at the start is used as the guard of the bump: if
another hold of this room committed in between, the bump matches no row, the attempt rolls back and
runs again against fresh state (rule DB-8). `maxAttempts` comes from
`roomlock.locking.optimistic.max-attempts` (3 by default); `1` disables retrying. The name is
scoped on purpose: the other two strategies pass a hard-coded single attempt, because giving them a
retry loop would change the very behaviour this comparison measures.

`room.version` is a plain column, never a JPA `@Version`, and `RoomRepository.bumpVersion` is the
only statement that writes it.

### Constraint — `ConstraintHoldStrategy`

```
INSERT booking (HELD) + INSERT outbox_event
  23P01 on booking_no_overlap -> Busy
```

It asks nothing. The insert either fits between the existing bookings or breaks `booking_no_overlap`,
and the violation *is* the answer. `ATTEMPTS = 1`.

The constraint is a PostgreSQL exclusion constraint over the generated `stay` column
(`booking-service/src/main/resources/db/constraint/V4__add_booking_no_overlap.sql`):

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking
  ADD CONSTRAINT booking_no_overlap
  EXCLUDE USING gist (room_id WITH =, stay WITH &&)
  WHERE (status IN ('HELD', 'PAYING', 'CONFIRMED'));
```

`btree_gist` is needed because the constraint mixes an equality operator on a `uuid` with the range
overlap operator; the partial `WHERE` is what makes a cancelled booking stop blocking its room.

## What a conflict costs

| | Pessimistic | Optimistic | Constraint |
| --- | --- | --- | --- |
| Statements on the happy path | lock, check, insert | read version, check, bump, insert | insert |
| What closes the window | a row lock held to commit | a conditional version bump | the exclusion constraint |
| A conflicting request | **waits** on the room row | **retries** the whole attempt | gets a **constraint violation** |
| Attempts | 1 | `roomlock.locking.optimistic.max-attempts` | 1 |
| Typical conflict outcome | `Busy` (the winner's row is visible by the time the loser reads) | `Contended`, retried; `Busy` once the winner's row is visible | `Busy` |
| Works without `booking_no_overlap` | yes | yes | **no** |
| Granularity of the conflict | the room | the room | the room **and** the stay |

The trade-off each one carries:

- **Pessimistic** is the simplest to reason about and it never rejects a request that could have
  succeeded — but it serialises every hold of a room, and the wait is held for the duration of the
  transaction, including the insert of the outbox row. Under contention throughput is bounded by one
  hold at a time per room, and the cost shows up as latency rather than as conflicts.
- **Optimistic** takes no lock, so uncontended holds never wait on each other — but a conflict costs
  a full rolled-back attempt, and beyond `maxAttempts` the client gets 409 `room-contended` and is
  asked to retry. It converts waiting into wasted work plus occasional client-visible retries.
- **Constraint** is the shortest path when the room is free: one round trip and no read at all. It is
  also the only one whose correctness lives entirely in the database, which is both its strength (no
  application race is possible) and its weakness (it is wrong the moment the constraint is missing —
  which is exactly what the `no-constraint` profile demonstrates).

## The `no-constraint` profile

Two of the three strategies check overlaps in the application. As long as `booking_no_overlap` is in
place, a bug in either check is invisible: the database would refuse the overlapping insert anyway.
Decision D1 removes that safety net on demand.

`spring.flyway.locations` is `classpath:db/migration, classpath:db/constraint` by default; the
`no-constraint` profile document at the bottom of `application.yml` resets it to
`classpath:db/migration` alone, so `V4__add_booking_no_overlap.sql` is never applied. That is the
only difference — `Profiles.NO_CONSTRAINT` is referenced nowhere else in production code.

What it proves, in `booking-service/src/test/java/dev/roomlock/booking/hold/NoConstraintHoldStrategyTest.java`
(annotated `@ActiveProfiles({Profiles.TEST, Profiles.NO_CONSTRAINT})`):

| Test | Strategies | Assertion |
| --- | --- | --- |
| `shouldReportBusyWhenStayOverlapsWithoutConstraint` | `PESSIMISTIC`, `OPTIMISTIC` | a second, overlapping hold answers `Busy` and the room still has one row — the *application* refused it |
| `shouldInsertOneBookingWhenHoldsRaceWithoutConstraint` | `PESSIMISTIC`, `OPTIMISTIC` | 8 simultaneous holds of the same stay produce exactly one `Inserted` and exactly one row |
| `shouldOverbookWhenConstraintStrategyRunsWithoutTheConstraint` | `CONSTRAINT` | two overlapping holds **both** succeed and the room ends up with 2 rows |

The third test is the honest one: it asserts the overbooking rather than hiding it, and its comment
says why — "the exclusion constraint is what makes this strategy correct". Without the profile that
statement would be an unverified claim.

`NoConstraintSchemaTest` covers the other half: that the profile really does skip the location (an
overlapping row can be inserted with plain SQL) and that Hibernate's `ddl-auto: validate` still passes
against the smaller schema, with no pending migrations. Because the containers are beans of the
context, the `no-constraint` context gets its own database through the context cache rather than
polluting the shared one.

For completeness, the counterpart with the constraint in place is
`HoldStrategyConcurrencyTest`: all three strategies, 8 racing holds of the same stay, exactly one
`Inserted` and exactly one row, and every other outcome one of `Busy` or `Contended`.

## Honest findings already recorded in the code

These are not caveats invented for this document; both are stated in the source.

### The optimistic strategy contends per room, not per stay

`room.version` is one counter for the whole room. Two holds of the same room for nights that do not
overlap still race for that counter, so one of them loses the bump and answers `Contended` even
though both stays were free. It is recorded twice:

- `HoldStrategyConcurrencyTest.shouldInsertEveryBookingWhenRacingHoldsAskForFreeStays` has to wrap
  each hold in up to `CLIENT_RETRIES = 10` client-side retries, with the javadoc "OPTIMISTIC counts
  one version per room, so holds of the same room contend even when their stays do not: such a hold
  answers room-contended and is retried, as a client does."
- `load-tests/src/gatling/java/dev/roomlock/loadtest/ThroughputSimulation.java` says the same about
  the conflict-free scenario: "Two holds of the same room for different nights are still not free of
  conflicts for every strategy — the optimistic one guards the room row, not the night", which is why
  409 `room-contended` remains an expected answer even there.

This is the price of guarding with a single scalar. A finer guard — a version per room and night, or
a separate availability row per night — would remove the false conflicts at the cost of a very
different schema.

### A database-side deadlock is treated as a lost race, not an error

When several inserts wait on `booking_no_overlap` at once, PostgreSQL can resolve the situation by
picking a loser and reporting a deadlock (SQLSTATE `40P01`) or a serialization failure (`40001`) — the
class-40 codes. That is the database making the same decision the application makes when a version
bump matches no row, so `TransactionalHoldStrategy.attemptOnce` catches Spring's
`ConcurrencyFailureException` and answers `Contended` instead of failing the request:

```java
} catch (ConcurrencyFailureException lostRace) {
  // A deadlock (40P01) or a serialization failure (40001): the database, not the application,
  // picked this attempt as the loser ...
  return new HoldOutcome.Contended();
}
```

`HoldConcurrencyFailureTest` pins that translation with scripted failures rather than a real race —
"a deadlock cannot be provoked on demand, and the translation is what needs pinning". It checks all
four cases: `40P01` and `40001` both become `Contended`; a retrying strategy inserts on the attempt
after a deadlock; and a failure that is *not* a lost race
(`DataAccessResourceFailureException`, "connection refused") still propagates.

The consequence for a reader of the metrics: a `contended` outcome under the `constraint` strategy,
which has no retry of its own, is almost always a class-40 code rather than an application decision.

## Comparing them

### What the metrics show

`booking-service/src/main/java/dev/roomlock/booking/hold/HoldMetrics.java` is the only place in the
hold flow that records, because every strategy funnels through `TransactionalHoldStrategy`.

| Meter | Type | Tags | Reads as |
| --- | --- | --- | --- |
| `roomlock.hold.outcomes` | counter | `strategy`, `outcome` = `inserted` \| `busy` \| `contended` \| `key_taken` | hold throughput by rate, conflict mix by outcome |
| `roomlock.hold.retries` | counter | `strategy` | attempts that ran only because the previous one lost the race; non-zero only for `optimistic` |
| `roomlock.hold.attempts` | timer with `publishPercentileHistogram` | `strategy` | p95/p99 of **one attempt**, so a retried hold contributes several samples |

Two deliberate details. The meters of **every** strategy are registered in the constructor, not on
first use, so a Grafana panel has a series to draw from the first scrape instead of a gap until the
first hold of that kind. And the timer records even when the attempt throws (`Timer.record(Supplier)`
wraps the call), so a failing attempt cannot hide from the latency panel.

The tag value is the lowercase strategy name — the same spelling `roomlock.locking.strategy` uses —
so a dashboard label matches the configuration value exactly.

`infra/grafana/dashboards/roomlock.json` turns those into a "Holds" row with four panels, all
filtered on `service="booking-service"`:

| Panel | Query |
| --- | --- |
| Holds per second by strategy | `sum by (strategy) (rate(roomlock_hold_outcomes_total{...}[...]))` |
| Hold attempt latency by strategy | `histogram_quantile(0.95 / 0.99, sum by (le, strategy) (rate(roomlock_hold_attempts_seconds_bucket{...}[...])))` |
| Conflicts per second by strategy | `sum by (strategy, outcome) (rate(roomlock_hold_outcomes_total{...}[...]))` |
| Retried attempts per second by strategy | `sum by (strategy) (rate(roomlock_hold_retries_total{...}[...]))` |

The dashboard also carries an "Outbox" row, a HikariCP row (active/idle/pending/max connections and
acquire time — the panel that exposes pessimistic locking starving the pool) and JVM rows.

### What the Gatling scenarios show

`load-tests/src/gatling/java/dev/roomlock/loadtest/` puts the same shape of load on the booking API
whatever the strategy. `roomlock.strategy` is **only a label** carried into the scenario names and the
printed report; the strategy itself is chosen by the service under test.

| Simulation | Injection | What it isolates |
| --- | --- | --- |
| `ContentionSimulation` | `atOnceUsers(users)`, all on the same room and the same night | the conflict path at maximum pressure. Exactly one 201, everybody else 409 `room-busy` or `room-contended`. |
| `ThroughputSimulation` | `constantConcurrentUsers(users).during(duration)` over the whole room pool and non-overlapping nights | what a strategy costs when almost nothing conflicts. |
| `IdempotentRetrySimulation` | `min(users, rooms)` users, each on a room of its own | that a replay of one `Idempotency-Key` answers 200 with the same booking id rather than a second booking or a 409. |

Three things make the comparison fair:

- **The fixture is created over HTTP** by a warmup scenario (`RoomCatalog.warmup`) that runs before
  the load phase via `andThen`, so a run needs nothing in the database and each run gets its own hotel
  and rooms. Stays start at 2030-01-01 so they cannot collide with anything else.
- **Expected conflicts are not failures.** `Holds.countedHold` checks `status().in(201, 409)`, so a
  409 passes the request; `HoldOutcomes` tallies outcomes per status and per problem type and prints
  the block after the run. `global().failedRequests().count().is(0L)` therefore fails a run only on an
  *unexpected* status — a 5xx, or a 400 from a malformed request.
- **Correctness is asserted, not just measured.** `ContentionSimulation` additionally asserts
  `details("read the booking of the winner").allRequests().count().is(1L)`: one room, one night, one
  winner, whatever the strategy. `IdempotentRetrySimulation` asserts that the replay actually
  happened, "otherwise the run proves nothing".

Comparing strategies in the containerised stack takes one variable:
`ROOMLOCK_LOCKING_STRATEGY=optimistic docker compose -f infra/docker-compose.yml up -d`. Running
booking-service next to the infrastructure is the other option, and the one to prefer while editing
code, since it needs no image rebuild — `README.md` has the exact commands. And
`.github/workflows/load-tests.yml` does the same on a runner with the strategy, user count and
duration as `workflow_dispatch` inputs, one run per strategy, `concurrency: load-tests` so two runs
cannot fight over the same ports and database.

## Known gaps

- **No numbers are recorded in the repository.** The scenarios and the dashboard make the three
  strategies comparable, but no measured result is committed anywhere, so this document deliberately
  describes costs qualitatively rather than quoting throughput or latency figures.
- **The `no-constraint` profile is untested for the API layer.** It is exercised by strategy and
  schema tests only; nothing asserts what `POST /api/v1/bookings` answers under it.
