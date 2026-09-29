# RoomLock

[![CI](https://github.com/eagapov-dev/roomlock/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/eagapov-dev/roomlock/actions/workflows/ci.yml)

Hotel room booking backend (Java 21, Spring Boot 3) that compares three locking strategies for concurrent reservations.

Build (JDK 21, Docker running): `./gradlew spotlessApply build`

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

Gatling scenarios in `load-tests/src/gatling/java` put the same contention on the booking API, so the three locking strategies can be compared on throughput, latency and conflict rates. Against the running compose stack:

```bash
./gradlew :load-tests:gatlingRun --all
```

| Simulation | What it exercises |
| --- | --- |
| `ContentionSimulation` | every virtual user holds the same room for the same night; exactly one 201, the rest 409 `room-busy` / `room-contended` |
| `ThroughputSimulation` | holds spread over the room pool and over nights that never overlap: the mostly conflict-free path |
| `IdempotentRetrySimulation` | the same hold twice with one `Idempotency-Key`; the replay has to answer 200 with the same booking id |

Each run creates its own hotel and rooms over HTTP, so nothing is expected in the database. The expected 409s are counted per problem type and printed after the run; a run only fails on an unexpected status. One simulation at a time: `--simulation=dev.roomlock.loadtest.ContentionSimulation` instead of `--all`.

Run parameters are system properties of the Gradle invocation:

| Property | Default | Meaning |
| --- | --- | --- |
| `roomlock.baseUrl` | `http://localhost:8090` | booking-service under test |
| `roomlock.strategy` | `unknown` | label of the strategy, reaches the scenario names and the output |
| `roomlock.users` | `50` | virtual users of the load phase |
| `roomlock.duration` | `30` | seconds the throughput scenario keeps its users busy |
| `roomlock.rooms` | `20` | rooms the setup phase creates |

The strategy itself is chosen by the service, not by the load test: `roomlock.locking.strategy` is `pessimistic`, `optimistic` or `constraint` (`constraint` by default). The compose file has no hook for it, so run booking-service next to the stack (infrastructure only, service on port 8080) and pass the strategy as an environment variable:

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.ide.yml up -d
ROOMLOCK_LOCKING_STRATEGY=optimistic ./gradlew :booking-service:bootRun
./gradlew :load-tests:gatlingRun --all --run-description "strategy=optimistic" \
  -Droomlock.baseUrl=http://localhost:8080 -Droomlock.strategy=optimistic \
  -Droomlock.users=100 -Droomlock.duration=60
```

`SPRING_APPLICATION_JSON='{"roomlock":{"locking":{"strategy":"optimistic"}}}'` does the same.

The HTML report of every run lands in `load-tests/build/reports/gatling/<simulation>-<timestamp>/index.html`. In CI the scenarios are a manual job: **Actions → Load tests → Run workflow**, with the strategy, the user count and the duration as inputs; it never runs on a pull request and uploads the reports as artifacts.
