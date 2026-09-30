# Runbook

Symptom, what to look at, what it means, what to do. The reasoning behind every mechanism named here
lives in [reliability](reliability.md) and [architecture](architecture.md); this document does not
repeat it, and a step that needs the reasoning links to it.

## Where the commands run

Two environments, and every section below gives the command for both.

| | Local (`infra/docker-compose.yml`) | Cloud (`cloud` profile, see [deployment](deployment.md)) |
| --- | --- | --- |
| Metrics | `curl -s localhost:8090/actuator/prometheus`, or Prometheus on `localhost:9090`, or the RoomLock dashboard in Grafana on `localhost:3000` | Managed Prometheus over PromQL — the `promql` shell function of [deployment](deployment.md#the-metrics) |
| Logs | `docker compose -f infra/docker-compose.yml logs -f <service>` | `gcloud logging read` with `resource.labels.service_name="<service>"` |
| Database | `docker compose -f infra/docker-compose.yml exec postgres psql -U roomlock -d booking` (also `-d payment`, `-d notification`) | the instance has **no public IP**; reach it through the Cloud SQL auth proxy with the `sql_instance_connection_name` output of `infra/gcp`. No procedure for this is committed. |
| Tokens | Keycloak on `localhost:8088`, realm `roomlock` — the two commands that fetch one are in the [README](../README.md) | whatever `OIDC_ISSUER_URI` names; the deployment brings its own provider ([deployment](deployment.md#2-the-infrastructure)) |
| Kafka | `docker compose -f infra/docker-compose.yml exec kafka /opt/kafka/bin/<tool>.sh --bootstrap-server kafka:9092 …` | the bootstrap address is reachable **only from inside the VPC** and the cluster accepts nothing but `SASL_SSL`/`OAUTHBEARER`, so the same tools need a client in the VPC configured the way the `cloud` profile is. `gcloud managed-kafka` administers topics; it does not read records. |

Host ports of the local stack are the defaults of `infra/.env.example`: booking-service 8090,
payment-service 8091 (gRPC 9091), notification-service 8092, PostgreSQL 5433 (`roomlock`/`roomlock`),
Keycloak 8088 (`admin`/`admin`).
Services run from the IDE listen on 8080/8081/8082 instead.

The four topics: `roomlock.booking-events.v1` and `roomlock.refund-requests.v1` (3 partitions each),
plus `roomlock.booking-events.v1.DLT` and `roomlock.refund-requests.v1.DLT` (1 partition each). The
`.DLT` suffix is `roomlock.notifications.dead-letter-suffix` and
`roomlock.payment.refunds.dead-letter-suffix`.

## Dead letter topics

**Nothing reads the dead-letter topics.** No service, no scheduled job, no tool. Everything in this
section is a human doing it by hand, and that is the whole reason the section exists.

**Symptom.** `Parking record from <topic>-<partition> offset <offset> on the dead-letter topic:
<message>` at ERROR in notification-service or payment-service. A guest who was never told about a
booking, or a refund that never happened, with no error anywhere else — the consumer moved to the next
offset and is healthy.

### What is parked

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 \
    --topic roomlock.booking-events.v1.DLT \
    --from-beginning --timeout-ms 10000 \
    --property print.headers=true \
    --property print.key=true \
    --property print.offset=true
```

`--timeout-ms` is what makes this terminate instead of tailing. Use
`roomlock.refund-requests.v1.DLT` for payment-service. To count without reading:

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 \
    --topic roomlock.booking-events.v1.DLT
```

In the cloud there is no `gcloud` command that reads a record. `gcloud managed-kafka topics describe
<topic> --cluster <cluster> --location <region> --project "$PROJECT"` confirms the topic exists and how
it is configured, and `gcloud managed-kafka topics list` shows all four; reading the records themselves
needs the same `kafka-console-consumer.sh` run from inside the VPC with the SASL properties of the
`cloud` profile.

### Reading the headers

`DeadLetterPublishingRecoverer` forwards the original record's key, value and headers and adds its own,
all prefixed `kafka_dlt-`. The ones worth reading:

| Header | What it tells you |
| --- | --- |
| `kafka_dlt-original-topic` | which topic the record came from — `roomlock.booking-events.v1` or `roomlock.refund-requests.v1` |
| `kafka_dlt-original-partition`, `kafka_dlt-original-offset` | exactly where it sat, so it can be found again in the source topic |
| `kafka_dlt-original-timestamp` | when it was produced, which is how an outage window is matched |
| `kafka_dlt-original-consumer-group` | `notification-service` or `payment-service` |
| `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message` | why the consumer gave up. This is the field that decides what to do next. |
| `kafka_dlt-exception-stacktrace` | the rest of it; long, and usually only needed once the message is not enough |

The record's own `event-type` header is forwarded unchanged, and it is the header the consumer reads to
decide which record of the contract to parse (`BookingEventReader`, `RefundEventReader`).

### Poison, or parked by an outage

Read `kafka_dlt-exception-fqcn`:

| Exception | What happened | Poison? |
| --- | --- | --- |
| `dev.roomlock.notification.infra.UnreadableEventException` or `dev.roomlock.payment.infra.UnreadableEventException` | no `event-type` header, an `event-type` that is not a type of the contract, no payload, or a payload that does not match the record | **Yes.** It is registered through `addNotRetryableExceptions` precisely because it will fail identically forever. It was parked on the first attempt, with no retries. Replaying it changes nothing. |
| anything else — a `DataAccessException`, a connection failure, a sender that threw | the handler failed in a way that may pass later. It was retried `…retry.attempts` times (2) `…retry.backoff` apart (1s) and then parked. | **No, probably.** This is the outage case: the database or the sender was away for more than about three seconds. Replaying it is the point. |

Two cross-checks before deciding:

- **The timestamp.** If `kafka_dlt-original-timestamp` falls inside a window where other records were
  also parked, or where the service was restarting, it is an outage rather than a bad record. A single
  parked record surrounded by healthy ones is the other way round.
- **Whether the effect actually happened.** A parked record is not proof that nothing happened: the
  handler may have committed and then failed. Check the target table before replaying —
  `SELECT * FROM notification WHERE event_id = '<eventId from the payload>';` in the `notification`
  database, or `SELECT * FROM payment WHERE booking_id = '<bookingId>';` in `payment`. If the row is
  there, the work is done and the parked record is noise.

For `RefundRequested` there is a third possibility the exception message names directly: a valid
booking event landed on the refund requests topic. `RefundEventReader` is stricter than
notification-service's reader on purpose — that topic carries exactly one type (rule K-2) — so even a
well-formed `BookingHeld` there is `UnreadableEventException`. That is poison, and it is also a bug
upstream; nothing in `KafkaEventSender.topicOf` can produce it today.

### Replaying one deliberately

A replay is publishing the record back to the topic named in `kafka_dlt-original-topic`. It is safe,
and it is safe for a specific reason rather than by luck: **both consumers deduplicate by event id.**
notification-service claims `notification.event_id`, which is `UNIQUE`, before it sends anything, so a
replay of an event already delivered is skipped with `Event <id> … was already delivered, skipping the
redelivery` at INFO. payment-service deduplicates by effect instead — `markRefunded` is conditional on
the payment still being `APPROVED` — so a replayed `RefundRequested` finds nothing to change and
answers `AlreadyRefunded`. See [reliability](reliability.md#idempotent-consumers).

Two things must survive the replay or it is not a replay:

1. **The key**, which is the booking id. It is what puts all events of one booking on one partition and
   keeps their order.
2. **The `event-type` header.** Without it the consumer throws `UnreadableEventException` and parks the
   record again — this time as poison.

`kafka-console-producer.sh` can send both. With `parse.headers=true` and `parse.key=true` the input
line is `h1:v1,h2:v2<headers.delimiter>key<key.separator>value`; both separators default to a tab, so
set them to something typable:

```bash
docker compose -f infra/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka:9092 \
    --topic roomlock.booking-events.v1 \
    --property parse.headers=true --property headers.delimiter='#' \
    --property parse.key=true     --property key.separator='|' \
    --property headers.separator=',' --property headers.key.separator=':'
```

and give it one line per record, on stdin:

```text
event-type:BookingConfirmed#<bookingId>|{"eventId":"…","bookingId":"<bookingId>","occurredAt":"…", …}
```

The `event-type` value is the simple class name of the contract record and nothing else —
`BookingHeld`, `BookingConfirmed`, `BookingCancelled`, `RefundRequested` — and it has to match the
payload, or the consumer parks the record as unreadable again.

Then confirm the effect rather than the absence of an error:

```bash
docker compose -f infra/docker-compose.yml logs --since 2m notification-service \
  | grep -E 'Notified guest|already delivered'

docker compose -f infra/docker-compose.yml exec postgres \
  psql -U roomlock -d notification -c \
  "SELECT event_id, event_type, kind, sent_at FROM notification WHERE booking_id = '<bookingId>' ORDER BY occurred_at;"
```

A `sent_at` that is `NULL` cannot happen: the send is inside the claim's transaction, so a row without
it never committed. Nothing removes the record from the `.DLT` topic — a replay adds to the source
topic and leaves the parked copy where it is. The `.DLT` topic is the log of what was parked, not a
queue, so note the offset you replayed somewhere a human will read.

## The outbox is not draining

**Symptom.** Events are not reaching Kafka: a booking is created but no guest is told, a confirmed
booking never produces a notification, a cancelled `CONFIRMED` booking is never refunded.

### Which metrics say so

```bash
curl -s localhost:8090/actuator/prometheus | grep -E '^roomlock_outbox'
```

| Metric | Reading it |
| --- | --- |
| `roomlock_outbox_unpublished` | the backlog: committed events with `published_at IS NULL`. It should sit at 0 and spike briefly. **Rising and not coming back is the symptom.** |
| `roomlock_outbox_stuck` | of that backlog, the events refused `roomlock.outbox.stuck-after` times (10) or more. Above 0 means at least one event is no longer an outage. It has no Grafana panel; read it here or by PromQL. |
| `roomlock_outbox_published_total` | flat while the backlog rises means nothing is going out at all. Rising while the backlog also rises means the sweep works and is simply behind. |
| `roomlock_outbox_failures_total` | refusals. Climbing in step with the sweep interval (1s) means every attempt is being refused. |

In the cloud: `promql 'sum(roomlock_outbox_unpublished)'`, `sum(roomlock_outbox_stuck)`,
`sum(roomlock_outbox_published_total)`.

### What each cause looks like

| Cause | The metrics | The logs |
| --- | --- | --- |
| **Broker unreachable** — wrong bootstrap address, broker down, in the cloud a SASL failure | backlog rises, `published_total` flat, `failures_total` climbs roughly once per sweep, `stuck` reaches >0 after about 10 sweeps | `Broker did not acknowledge <type> of booking <id>` as the cause of an `EventSendFailedException`, and `Outbox stopped at <type> of booking <id> after <n> attempt(s)` at WARN. In the cloud also `SaslAuthenticationException`. Every booking is affected, so the WARNs name many different booking ids. |
| **One poison event holding up its own booking** | backlog small and roughly constant, `stuck` small and constant, `published_total` still rising — other bookings are going out fine | the same booking id, over and over: `Outbox event <id> (<type> of booking <id>) has been refused <n> times and is holding up that booking` at ERROR, once per sweep. One booking id rather than many is what distinguishes this from a broker problem. |
| **A stale lease held by a dead instance** | backlog rises, `published_total` flat, **`failures_total` also flat** — nothing is being attempted at all | `Another instance holds the publisher lease, skipping this sweep` at DEBUG on every live instance, and nothing else. This resolves itself within `roomlock.outbox.lease` (30s): the lease expires and the next sweep takes it over. A backlog that rises for *longer* than that with no failures means something other than the lease. |

The third row is why `failures_total` is the useful second number: a refused event and an event nobody
tried are both a rising backlog, and only the failure counter tells them apart.

### Looking at `outbox_event` directly

```sql
-- the shape of the backlog
SELECT type, count(*), min(created_at) AS oldest, max(attempts) AS worst
FROM outbox_event
WHERE published_at IS NULL
GROUP BY type;

-- the events that are stuck rather than merely waiting (stuck-after is 10)
SELECT id, aggregate_id, type, attempts, created_at
FROM outbox_event
WHERE published_at IS NULL AND attempts >= 10
ORDER BY created_at;

-- everything still waiting for one booking, in the order it has to go out
SELECT id, type, attempts, created_at
FROM outbox_event
WHERE aggregate_id = '<bookingId>' AND published_at IS NULL
ORDER BY created_at;

-- who holds the publisher lease, and whether it has expired
SELECT id, holder, acquired_at, expires_at, expires_at <= now() AS expired
FROM outbox_lease;

-- how much history the prune has left behind
SELECT count(*) AS published, min(published_at) AS oldest_kept
FROM outbox_event
WHERE published_at IS NOT NULL;
```

`aggregate_id` is the booking id. The oldest row of the backlog and the oldest row of one booking are
the two numbers worth writing down before touching anything.

**What not to do.** Do not `UPDATE outbox_event SET published_at = now()` to make the backlog go away.
That drops a committed event, which is the one failure the outbox exists to prevent, and no consumer
can repair it. There is deliberately no supported way to move a stuck event aside
([reliability, known gaps](reliability.md#known-gaps)); fix what the broker is refusing instead.

**What to do.** For a broker problem, fix the broker or the credentials — the backlog drains on its own
once a send succeeds, oldest first, and nothing was lost. For the poison event, read the exception under
the ERROR line; it is a contract or configuration problem (a type `KafkaEventSender.topicOf` does not
know, a topic that does not exist and cannot be auto-created), and the fix belongs in the code or the
cluster, not in the table. For a lease that is genuinely stale, wait 30 seconds.

## A booking is stuck in PAYING

**Symptom.** `GET /api/v1/bookings/{id}` answers `"status": "PAYING"` and stays there. The guest got a
503 `urn:roomlock:problem:payment-unavailable`, or nothing at all.

**What it means.** The `HELD → PAYING` transition committed and the `Charge` call did not come back
inside `roomlock.payment.deadline` (3s). This is the one state the database cannot resolve alone: the
money may or may not have moved. `PAYING` still holds the room, so the stay is not double-booked while
this is unresolved.

### What the sweep does

`PaymentReconcileScheduler` runs `PaymentReconciler.reconcileBatch` every
`roomlock.payment.reconcile.interval` (10s). Each sweep leases up to `…reconcile.batch-size` (100)
bookings that have been `PAYING` longer than `…reconcile.after` (1m) —
`BookingRepository.leaseStalePayments`, which re-stamps `payment_started_at` so no other sweep takes
the same booking for another minute — asks `Charge` again, and applies the answer through the same
conditional transitions a request uses. Asking again is free because the booking id **is** the
idempotency key: payment-service replays the decision it already made.

**It does not give up.** A provider that keeps answering nothing leaves the booking `PAYING` and
re-leased every minute for as long as the service runs. There is no dead-end state and no attempt
counter on a booking ([reliability, known gaps](reliability.md#known-gaps)). What ends the wait is the
provider answering, or a human.

So the first question is how long it has been there, not whether the sweep ran:

```sql
SELECT id, status, payment_started_at, now() - payment_started_at AS since_lease, updated_at
FROM booking WHERE id = '<bookingId>';

-- every booking the sweep is currently responsible for
SELECT id, guest_id, total_price, payment_started_at
FROM booking
WHERE status = 'PAYING' AND payment_started_at <= now() - interval '1 minute'
ORDER BY payment_started_at;
```

`payment_started_at` moving forward about once a minute with the status unchanged is the sweep working
and the provider not answering. `payment_started_at` older than a minute and *not* moving means the
sweep is not running at all — check that the process has `@Scheduled` enabled (it is off under the
`test` profile) and, in the cloud, that the revision has `cpu_idle = false` and an instance, because a
throttled instance stops sweeping.

### What the provider answered

booking-service side, per attempt:

```bash
docker compose -f infra/docker-compose.yml logs booking-service \
  | grep -E 'Reconciled booking|stays PAYING|got no answer'
```

- `Reconciled booking <id> left PAYING: Confirmed|Declined|AlreadyConfirmed|NotPayable` — the sweep
  settled it; the booking is no longer `PAYING`.
- `Reconciled booking <id> left PAYING: ProviderUnavailable` — still nothing; it waits for the next
  sweep.
- `Charge of booking <id> got no answer: <gRPC status>` at WARN, from `GrpcPaymentGateway`. The status
  is the diagnosis: `DEADLINE_EXCEEDED` is a slow provider, `UNAVAILABLE` is one that is not there,
  `UNAUTHENTICATED` in the cloud is the ID token of `CloudRunAuthInterceptor`, and
  `FAILED_PRECONDITION` is the booking having been charged with a different guest, amount or currency —
  which retrying will never fix.
- `Booking <id> stays PAYING, the provider gave no answer: <detail>` at WARN, from `PaymentService`.

payment-service side, which is the authority on whether money moved:

```bash
docker compose -f infra/docker-compose.yml logs payment-service | grep 'Charge of booking'

docker compose -f infra/docker-compose.yml exec postgres psql -U roomlock -d payment -c \
  "SELECT id, status, amount_minor, currency, decline_reason, charged_at, refunded_at
   FROM payment WHERE booking_id = '<bookingId>';"
```

`Charge of booking <id> is APPROVED|DECLINED: payment <id>, <amount> <currency>` appears **once** per
booking, ever — a second line for one booking would mean the guest was charged twice, which
`payment_booking_id_unique` makes impossible. In the cloud these are the two `gcloud logging read`
queries of [deployment](deployment.md#that-the-events-arrived).

The three cases the payment row settles:

| `payment` row | What it means | What to do |
| --- | --- | --- |
| no row | nothing was ever charged; the call never reached the provider | nothing: the next sweep charges for real. Fix the transport if it keeps failing. |
| `APPROVED` | the money moved and booking-service never heard | nothing: the next successful sweep reads the same decision back and confirms the booking. If the sweep is not running, that is the problem to fix, not the payment. |
| `DECLINED` | the provider refused | nothing: the next sweep cancels the booking with `PAYMENT_DECLINED`. |

In every case the action is to make the sweep able to reach the provider, never to change the booking
row by hand: a status written outside `PaymentTransitions` skips the outbox event, and the guest is
then never told (rule K-1).

## Holds are not expiring, or a room looks permanently taken

**Symptom.** `POST /api/v1/bookings` answers 409 `urn:roomlock:problem:room-busy` for a stay nobody
should be holding, and keeps doing so past the ten minutes a hold is supposed to live.

**What holds a room.** `HELD`, `PAYING` and `CONFIRMED` all hold it; only `CANCELLED` releases it. Two
things enforce that, separately: `booking_no_overlap` — `EXCLUDE USING gist (room_id WITH =, stay WITH
&&) WHERE (status IN ('HELD','PAYING','CONFIRMED'))`, created by `db/constraint/V4` and absent under
the `no-constraint` profile — and `BookingRepository.existsActiveOverlap`, which the strategies read.
`stay` is the generated half-open `daterange [check_in, check_out)`, so a stay that checks out on the
day another checks in does **not** overlap.

### Which booking holds the room

```sql
-- everything holding this room, with the stay as the constraint sees it
SELECT id, guest_id, status, cancel_reason, stay, hold_expires_at, payment_started_at, created_at
FROM booking
WHERE room_id = '<roomId>' AND status IN ('HELD', 'PAYING', 'CONFIRMED')
ORDER BY check_in;

-- what blocks one specific stay: the exact predicate of booking_no_overlap
SELECT id, guest_id, status, stay, hold_expires_at
FROM booking
WHERE room_id = '<roomId>'
  AND status IN ('HELD', 'PAYING', 'CONFIRMED')
  AND stay && daterange('<checkIn>', '<checkOut>', '[)');

-- holds past their expiry and still HELD: exactly what the sweep should be taking
SELECT id, room_id, hold_expires_at, now() - hold_expires_at AS overdue
FROM booking
WHERE status = 'HELD' AND hold_expires_at <= now()
ORDER BY hold_expires_at;
```

If the third query returns rows that stay there, the expiry sweep is not running. If it returns nothing
and a room is still blocked, read the `status` column of the first query — the room is held by a
`PAYING` booking (go to [the PAYING section](#a-booking-is-stuck-in-paying)) or by a `CONFIRMED` one,
which is not a fault: a confirmed stay holds its room until somebody cancels it.

### The expiry sweep

`HoldExpiryScheduler` runs `LifecycleService.expireHolds` every `roomlock.hold.expiry.interval` (30s),
cancelling at most `…expiry.batch-size` (500) `HELD` bookings whose `hold_expires_at` has passed, with
`cancel_reason = 'HOLD_EXPIRED'`, and writing one `BookingCancelled` per booking in the same
transaction. `hold_expires_at` is stamped at creation from `roomlock.hold.ttl` (10m), so a hold lives
at most the TTL plus one sweep interval.

```bash
docker compose -f infra/docker-compose.yml logs booking-service | grep 'Hold expiry cancelled'
```

`Hold expiry cancelled <n> booking(s)` at INFO, and only when `n > 0` — silence means either nothing
was due or the sweep is not running, which the SQL above tells apart. The statement uses
`FOR UPDATE SKIP LOCKED`, so a row a live request is writing is passed over and taken by the next
sweep; a single overdue hold that disappears within 30 seconds is that, not a fault.

If the sweep is not running: `@Scheduled` is off under the `test` profile (`SchedulingConfig`), and in
the cloud a revision without `cpu_idle = false`, or without an instance, stops sweeping between
requests.

**Releasing a room by hand** means writing `status = 'CANCELLED'` **and** `cancel_reason` together —
`booking_cancel_reason_consistent` refuses either without the other — and it skips the
`BookingCancelled` event entirely, so the guest is never told and, for a `CONFIRMED` booking, no
`RefundRequested` is written and the money stays taken. Prefer
`POST /api/v1/bookings/{id}/cancel`, which does the transition and the events the way the code does.

## A consumer is falling behind

**Symptom.** Events are published — `roomlock_outbox_unpublished` is 0 and
`roomlock_outbox_published_total` is rising — but the effect arrives late: guests are told minutes
after the booking, refunds lag behind cancellations.

### Reading the lag

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
    --describe --group notification-service
```

The groups are `notification-service` (on `roomlock.booking-events.v1`) and `payment-service` (on
`roomlock.refund-requests.v1`) — `spring.kafka.consumer.group-id` in each `application.yml`. To list
them and read the state of one:

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --list

docker compose -f infra/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
    --describe --group payment-service --state
```

What the columns say:

- **`LAG` per partition.** Growing on every partition is a consumer that cannot keep up, or is not
  consuming at all. Growing on one partition only is a hot key — all events of one booking share a
  partition, because `bookingId` is the key — or one slow handler.
- **`CONSUMER-ID` / `HOST` empty with lag present.** No member is assigned: the consumer is down, or
  it cannot authenticate to the broker. Read the service log, not the lag.
- **`--state` showing `PreparingRebalance` or `CompletingRebalance` for more than a few seconds.** A
  member is joining and leaving repeatedly. In the cloud both services set `session.timeout.ms: 30000`
  and `heartbeat.interval.ms: 10000` so a stopped revision is noticed in seconds; a handler that takes
  longer than `max.poll.interval.ms` (the default five minutes, deliberately left alone) is evicted
  mid-record and the record is redelivered.

A large lag right after a deployment is expected and is not a fault: both services set
`auto-offset-reset: earliest`, so a **new** consumer group reads the whole topic from the beginning on
purpose. The `notification.event_id` constraint and the `payment` row, not the offset, are what keep
that from duplicating any effect — it churns through the backlog and skips almost all of it, logging
`… was already delivered, skipping the redelivery`.

### How much parallelism is even possible

**The partition count is the ceiling.** One partition is consumed by at most one member of a group, so:

| Topic | Partitions | Maximum useful consumers in the group |
| --- | --- | --- |
| `roomlock.booking-events.v1` | 3 | 3 |
| `roomlock.refund-requests.v1` | 3 | 3 |
| both `.DLT` topics | 1 | 1, and nothing consumes them |

Three is `KAFKA_NUM_PARTITIONS` of the compose broker, `OutboxTopics.PARTITIONS` of the `NewTopic`
beans and `var.kafka_partitions` of `infra/gcp`, kept the same on purpose so a group behaves the same
locally and deployed.

Neither service sets `spring.kafka.listener.concurrency`, so each instance runs **one** consumer thread
per listener container and one instance consumes all three partitions on it. And `max_instance_count`
defaults to 1 in `infra/gcp/variables.tf`, so the deployed parallelism is 1 of a possible 3. Check the
topic before concluding anything about throughput:

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
    --describe --topic roomlock.booking-events.v1
```

Raising `concurrency` to 3, or running up to 3 instances, is the first lever, and it stops helping at 3.
Adding partitions later is not free: it changes which partition a booking id hashes to, so events
written before and after the change can be handled out of order for a booking that spans it.

Before blaming Kafka, check the handler. notification-service calls its sender **inside** the database
transaction ([reliability, known gaps](reliability.md#known-gaps)), so a slow channel holds a Hikari
connection and throttles the consumer directly. `hikaricp_connections_pending` and
`hikaricp_connections_acquire_seconds` on `/actuator/prometheus` — the "Connection pool" row of the
Grafana dashboard — say whether that is what is happening.

## Every request answers 401

`/actuator/health` is fine, the database is fine, and every call under `/api/v1` comes back
`401 urn:roomlock:problem:unauthenticated`. booking-service validates an access token against the JWK
set of `OIDC_ISSUER_URI` ([architecture](architecture.md#authentication-and-authorization)), and it
deliberately does not say which of the reasons applied — so it has to be narrowed down from outside.

Read the token first; the answer is usually in it.

```bash
# The payload of the token being sent. No verification, just the claims.
printf '%s' "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | jq '{iss, sub, exp, scope}'

# What the service was told to trust, and what the clock of the machine minting tokens thinks.
date -u
```

Four causes, in the order they actually happen:

| What you see | Cause | What to do |
| --- | --- | --- |
| `exp` is in the past, or within a minute of now | the token expired — the local realm gives 30 minutes, and a Gatling run fetches one token before it starts | get a new one; a run longer than the token's lifetime needs a longer `accessTokenLifespan` |
| `iss` of the token differs from `OIDC_ISSUER_URI`, by so much as a trailing slash or `http` against `https` | the revision was pointed at a different issuer than the one that minted the token. Locally this is the compose case: Keycloak's `KC_HOSTNAME` pins `iss` to `localhost:8088`, and a token fetched from somewhere else carries something else | make the two strings identical. There is no normalisation and no wildcard |
| all claims look right, and the service log has `An error occurred while attempting to decode the Jwt` with a connect timeout or an unknown host | **the issuer is unreachable from the service**, so the JWK set cannot be fetched. This is the first request's failure and not the rollout's: the keys are fetched lazily, so the revision started healthy and only then began refusing everything. In the cloud it means `OIDC_JWK_SET_URI` is outside what direct VPC egress routes (`egress = "PRIVATE_RANGES_ONLY"` in `infra/gcp/run.tf`) or the provider is down; in compose it means booking-service is still resolving `localhost` instead of `keycloak:8080` | `curl` the JWK set from where the service runs, not from your laptop: `docker compose -f infra/docker-compose.yml exec booking-service curl -fsS "$OIDC_JWK_SET_URI"`, or `gcloud run services describe` and check which URI the revision was given |
| `iss` and `exp` are right, the issuer is reachable, and it still fails — often right after a laptop resumed from sleep or a VM's clock drifted | **clock skew**. A token whose `iat`/`nbf` is in the service's future, or whose `exp` has already passed by the service's clock, is refused with no allowance beyond Spring Security's default 60s | fix the clock on whichever side is wrong (`date -u` on both), do not widen the allowance |

Two answers that look like this one but are not:

- **403 with `urn:roomlock:problem:forbidden`** — the token is valid and the endpoint wanted a scope it
  does not carry. Only `POST /api/v1/hotels` and `POST /api/v1/hotels/{id}/rooms` ask for one
  (`catalog:write`); everything else under `/api/v1/bookings` needs no scope at all.
- **403 with an HTML body and no `urn:roomlock:problem:` type** — in the cloud that is Cloud Run's IAM
  check answering before the container ever saw the request. That one reads
  `X-Serverless-Authorization`, not `Authorization`; see
  [deployment, verification](deployment.md#4-verification).

And one that is nothing to do with tokens: **404 on a booking that exists.** A booking of another guest
answers 404 on purpose, so that nothing about the answer says whether the id is real. Check the `sub`
of the token against `guest_id` of the row before looking for a bug.

```sql
SELECT id, guest_id, status FROM booking WHERE id = '<booking id>';
```

## Rolling back

A rollback is a traffic change, not a build: Cloud Run keeps the previous revisions. The commands, the
order to roll back in (booking-service, then notification-service, then payment-service, so the caller
is never newer than the provider it charges), and the one thing a rollback does **not** undo — a Flyway
migration, which only goes forwards — are in
[deployment, rolling back a deploy](deployment.md#6-rolling-back-a-deploy). Do not reconstruct them
from here.

Locally there is nothing to roll back: rebuild the images and restart.

```bash
./gradlew jibDockerBuild
docker compose -f infra/docker-compose.yml up -d
```

`docker compose -f infra/docker-compose.yml down -v` deletes the volumes with everything in them —
bookings, payments, notifications, the outbox and every Kafka topic including the `.DLT` ones. Never
run it while a parked record still matters.
