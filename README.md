# RoomLock

[![CI](https://github.com/eagapov-dev/roomlock/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/eagapov-dev/roomlock/actions/workflows/ci.yml)

Hotel room booking backend (Java 21, Spring Boot 3) that compares three locking strategies for concurrent reservations.

Build (JDK 21, Docker running): `./gradlew spotlessApply build`

## Architecture

Six Gradle modules: booking-service holds rooms and owns the booking lifecycle, payment-service
charges and refunds over gRPC, notification-service tells the guest what happened, and `common`,
`payment-api` and `load-tests` carry the Kafka contract, the gRPC contract and the Gatling scenarios.
Each service owns one PostgreSQL database and they talk to each other only over one gRPC call and two
Kafka topics. The same hold use case sits behind three interchangeable locking strategies — a
pessimistic row lock, an optimistic version bump with retries, and a PostgreSQL exclusion constraint —
and a profile removes the database guard so the application-level ones can be proven rather than
assumed. Holds are idempotent per guest and key, every event crosses the wire through a transactional
outbox, and both consumers plus the payment provider are idempotent.

- [docs/architecture.md](docs/architecture.md) — modules, request flow, data ownership, the events and
  their topics, and the booking lifecycle as a state machine
- [docs/locking-strategies.md](docs/locking-strategies.md) — the three strategies, what each conflict
  costs, the `no-constraint` profile, and how the metrics and load tests compare them
- [docs/reliability.md](docs/reliability.md) — idempotent holds, the transactional outbox, idempotent
  consumers, the idempotent charge and refund, and the sweeps that clean up after a crash
- [docs/deployment.md](docs/deployment.md) — the same architecture on Google Cloud as a one-off
  demonstration: the `cloud` profile, the images, the deploy workflow, how to verify it against the
  real thing and how to tear it down again (nothing in it has been run against a real project yet)

## Running locally

Requires JDK 21 and Docker. Build the three service images (Jib, into the local Docker daemon), then start everything with one command:

```bash
./gradlew jibDockerBuild
docker compose -f infra/docker-compose.yml up -d
```

This starts PostgreSQL 16 (databases `booking`, `payment`, `notification`), Kafka in KRaft mode, Prometheus, Grafana and the three services. Host ports and local credentials are in `infra/.env.example`; the compose file has the same values as defaults, so copy it to `infra/.env` (git-ignored) only to change something, for example a port that is taken.

| Component | Host address |
| --- | --- |
| booking-service | http://localhost:8090 (`/swagger-ui.html`, `/actuator/health`, `/actuator/prometheus`) |
| payment-service | http://localhost:8091, gRPC `localhost:9091` (`roomlock.payment.v1.PaymentService`) |
| notification-service | http://localhost:8092 |
| PostgreSQL | `localhost:5433`, user `roomlock` / `roomlock` |
| Kafka | `localhost:9094` (inside compose: `kafka:9092`) |
| Prometheus | http://localhost:9090 (targets: `/targets`) |
| Grafana | http://localhost:3000, `admin` / `admin`, dashboard RoomLock |

Infrastructure in Docker, services from the IDE (the services run on their `application.yml` ports 8080/8081/8082, Prometheus scrapes them on the host):

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.ide.yml up -d
```

Stop and delete all data: `docker compose -f infra/docker-compose.yml down -v`.

## Load tests

Gatling scenarios in `load-tests/src/gatling/java` put the same contention on the booking API, so the three locking strategies can be compared on throughput, latency and conflict rates — while holding a room, and while paying for it. Against the running compose stack:

```bash
./gradlew :load-tests:gatlingRun --all
```

| Simulation | What it exercises |
| --- | --- |
| `ContentionSimulation` | every virtual user holds the same room for the same night; exactly one 201, the rest 409 `room-busy` / `room-contended` |
| `ThroughputSimulation` | holds spread over the room pool and over nights that never overlap: the mostly conflict-free path |
| `IdempotentRetrySimulation` | the same hold twice with one `Idempotency-Key`; the replay has to answer 200 with the same booking id |
| `ConfirmFlowSimulation` | the whole funnel: hold a room, pay for it, read the booking back as `CONFIRMED` |
| `DeclineFlowSimulation` | the same funnel with a charge that is declined: 409 `payment-declined` and the booking read back as `CANCELLED` |
| `IdempotentPaySimulation` | the same booking paid twice; both answers 200 with the same booking id and status `CONFIRMED`, so nobody is charged twice |

Each run creates its own hotel and rooms over HTTP, so nothing is expected in the database. The expected 409s are counted per problem type and printed after the run — holds and charges in a tally of their own — and a run only fails on an unexpected status: a 5xx that is not the documented 503 `payment-unavailable`, or a 400. One simulation at a time: `--simulation=dev.roomlock.loadtest.ContentionSimulation` instead of `--all`.

The three confirm-flow scenarios pay over `POST /api/v1/bookings/{id}/pay`, so payment-service has to be up as well (it is part of the compose stack). Its decision is deterministic: `roomlock.payment.decision.mode` is `THRESHOLD` (decline above `decline-above-minor`, 1000.00 PLN by default), `APPROVE_ALL` or `DECLINE_ALL`. `DeclineFlowSimulation` therefore needs no reconfiguration — it prices its rooms at `roomlock.declinedPricePerNight` (2500.00), above the threshold, and the charge is declined on the amount alone. To force a decline in every scenario instead, run the provider in `DECLINE_ALL` and tell the load test which mode to expect:

```bash
ROOMLOCK_PAYMENT_DECISION_MODE=DECLINE_ALL ./gradlew :payment-service:bootRun
./gradlew :load-tests:gatlingRun --simulation=dev.roomlock.loadtest.DeclineFlowSimulation \
  -Droomlock.paymentMode=DECLINE_ALL
```

Run parameters are system properties of the Gradle invocation:

| Property | Default | Meaning |
| --- | --- | --- |
| `roomlock.baseUrl` | `http://localhost:8090` | booking-service under test |
| `roomlock.strategy` | `unknown` | label of the strategy, reaches the scenario names and the output |
| `roomlock.users` | `50` | virtual users of the load phase |
| `roomlock.duration` | `30` | seconds the throughput and confirm-flow scenarios keep their users busy |
| `roomlock.rooms` | `20` | rooms the setup phase creates |
| `roomlock.pricePerNight` | `120.00` | price of those rooms, and so the total a one-night stay is charged |
| `roomlock.declinedPricePerNight` | `2500.00` | the same for the decline flow: above the threshold of the provider |
| `roomlock.paymentMode` | `THRESHOLD` | label of the decision mode the provider runs with, reaches the names and the output |

The strategy itself is chosen by the service, not by the load test: `roomlock.locking.strategy` is `pessimistic`, `optimistic` or `constraint` (`constraint` by default). The compose file passes `ROOMLOCK_LOCKING_STRATEGY` through to booking-service, so a containerised run compares the three without leaving the stack — put it in `infra/.env` to keep it across `up`s:

```bash
ROOMLOCK_LOCKING_STRATEGY=optimistic docker compose -f infra/docker-compose.yml up -d
./gradlew :load-tests:gatlingRun --all --run-description "strategy=optimistic" \
  -Droomlock.strategy=optimistic -Droomlock.users=100 -Droomlock.duration=60
```

Running booking-service next to the infrastructure is the other way (service on port 8080): no image rebuild after a code change, and it is what the CI job below does.

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.ide.yml up -d
ROOMLOCK_LOCKING_STRATEGY=optimistic ./gradlew :booking-service:bootRun
./gradlew :payment-service:bootRun  # the confirm-flow scenarios charge against it
./gradlew :load-tests:gatlingRun --all --run-description "strategy=optimistic" \
  -Droomlock.baseUrl=http://localhost:8080 -Droomlock.strategy=optimistic \
  -Droomlock.users=100 -Droomlock.duration=60
```

`SPRING_APPLICATION_JSON='{"roomlock":{"locking":{"strategy":"optimistic"}}}'` does the same in either.

The HTML report of every run lands in `load-tests/build/reports/gatling/<simulation>-<timestamp>/index.html`. In CI the scenarios are a manual job: **Actions → Load tests → Run workflow**, with the strategy, the simulations of the run (all, the three hold scenarios, the three confirm-flow ones, or a single one), the payment decision mode, the user count and the duration as inputs; it starts booking-service and payment-service from their boot jars next to the infrastructure, never runs on a pull request, and uploads the reports and the service logs as artifacts.
