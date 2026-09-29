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
| payment-service | http://localhost:8091 |
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
