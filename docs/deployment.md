# Deployment

RoomLock on Google Cloud, with the architecture unchanged: the three services on **Cloud Run**, one
**Cloud SQL for PostgreSQL** instance with the three databases, **Google Managed Service for Apache
Kafka** for the two topics, images in **Artifact Registry**. No substitutions — no Pub/Sub instead of
Kafka, no embedded database, no sidecar proxy.

> **Nothing in this document has been executed against a real project.** The `cloud` profile, the
> deploy workflow and the OpenTofu of `infra/gcp` are written against the provider schema and the
> documentation of each service, and what can be checked without a project is checked: the profile
> documents bind and name their variables (`CloudProfileTest` in each service), the ID token reaches
> the call metadata (`CloudRunAuthInterceptorTest`), the workflow is valid YAML with valid shell, and
> Jib publishes to a configurable target. What is *not* verified is everything that needs an account:
> whether the federation trusts the right repository, whether the broker accepts the token the login
> handler signs, whether a revision actually becomes healthy, and whether the pinned versions of the
> `google-github-actions` actions are the current ones. Read every command below as a first attempt,
> not as a transcript.

This is also a **one-off demonstration**, not a standing environment. It exists to show the system
running in the cloud, and step 5 destroys it again. The Kafka cluster and the Cloud SQL instance bill
for existing rather than for being used, so an idle demonstration costs the same as a busy one.

## What runs where

| Piece | Where | Notes |
| --- | --- | --- |
| booking-service | Cloud Run, HTTP on the ingress port | the only service a person calls, and the only one that validates a guest's access token |
| the identity provider | **not here — bring your own** | nothing in `infra/gcp` runs one; booking-service is pointed at an existing issuer with `OIDC_ISSUER_URI` and `OIDC_JWK_SET_URI` (see below) |
| payment-service | Cloud Run, **gRPC** on the ingress port (`h2c`) | internal ingress, authentication required |
| notification-service | Cloud Run, HTTP on the ingress port | consumes only; nobody calls it |
| `booking`, `payment`, `notification` | one Cloud SQL instance, private IP | one database per service, as locally |
| `roomlock.booking-events.v1`, `roomlock.refund-requests.v1` (+ `.DLT`) | Managed Service for Apache Kafka | SASL_SSL, OAUTHBEARER |
| images | Artifact Registry, tagged with the commit SHA | `<region>-docker.pkg.dev/<project>/<repo>/<service>:<sha>` |
| metrics | Google Managed Prometheus | a sidecar scrapes `/actuator/prometheus` of each instance |
| logs | Cloud Logging | stdout of the containers |

Each revision runs with the `cloud` profile (`SPRING_PROFILES_ACTIVE=cloud`), which is the only thing
that switches the services from the addresses of a laptop to the addresses of the project — see the
`cloud` document at the end of each `src/main/resources/application.yml`.

Two consequences of Cloud Run worth knowing before anything else:

- **A service routes ingress to one container port.** payment-service serves HTTP on 8081 and gRPC on
  9091, and the ingress port is the gRPC one, because a gRPC call from booking-service is what has to
  cross the network. Its HTTP port stays on localhost: the metrics sidecar scrapes it and the startup
  and liveness probes read `/actuator/health` on it, and **nothing outside the instance can curl it**.
  The health of payment-service is visible in the probe and in Cloud Logging, never in a request.
- **The sweeps are not request-driven.** The outbox sweep, the hold expiry sweep and the payment
  reconciliation sweep are `@Scheduled`, so every revision needs CPU between requests: CPU always
  allocated and at least one instance. A throttled instance stops sweeping, and a booking that was
  paid for stops being reported to the guest.

## 1. Bootstrap, by hand, once

Nothing here is automated, and nothing here is in this repository. A human with owner rights on the
project does it once:

1. **A project and billing.** `gcloud projects create roomlock-demo --name RoomLock` and attach a
   billing account. Pick one region for everything and stay in it; the examples use `europe-central2`.
2. **The APIs.** Enabling them is the one part of the project the OpenTofu cannot do for itself,
   because it needs them to exist before it can plan:

   ```bash
   gcloud services enable \
     run.googleapis.com sqladmin.googleapis.com managedkafka.googleapis.com \
     artifactregistry.googleapis.com secretmanager.googleapis.com \
     compute.googleapis.com servicenetworking.googleapis.com \
     iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com \
     monitoring.googleapis.com logging.googleapis.com \
     --project roomlock-demo
   ```

3. **A bucket for the state.** The state of the infrastructure cannot live in the repository:

   ```bash
   gcloud storage buckets create gs://roomlock-demo-tfstate \
     --project roomlock-demo --location europe-central2 --uniform-bucket-level-access
   gcloud storage buckets update gs://roomlock-demo-tfstate --versioning
   ```

4. **Run step 2 once as a human**, because the deploy workflow cannot authenticate before the identity
   pool it authenticates with exists.
5. **The variables of the GitHub environment.** Create an environment (`demo`, say) under
   *Settings → Environments* and give it these **variables** — they are names, not secrets, and the
   workflow reads nothing else about the target:

   | Variable | Example | Comes from |
   | --- | --- | --- |
   | `GCP_PROJECT_ID` | `roomlock-demo` | step 1 |
   | `GCP_REGION` | `europe-central2` | step 1 |
   | `GCP_ARTIFACT_REPOSITORY` | `roomlock` | output of step 2 |
   | `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/123456789/locations/global/workloadIdentityPools/github/providers/roomlock` | output of step 2 |
   | `GCP_DEPLOY_SERVICE_ACCOUNT` | `roomlock-deploy@roomlock-demo.iam.gserviceaccount.com` | output of step 2 |

   No **secrets** are needed for a deploy: Workload Identity Federation exchanges the OIDC token of
   the workflow run for a Google access token, so there is no key to store and no `docker login` with
   a password. The only secret of the deployment at all is the database password, and it lives in
   Secret Manager where the revisions read it — not in GitHub. Restrict the environment to `main` and
   add reviewers if the project is worth protecting.

## 2. The infrastructure

Everything in the table above except the images and the revisions' image tags is OpenTofu in
[`infra/gcp/`](../infra/gcp) — the network and the private services access, the Cloud SQL instance and
its databases, the Kafka cluster and its topics, the Artifact Registry repository, the three Cloud Run
services with their service accounts and IAM, the Secret Manager secret, the Managed Prometheus
scraping, and the Workload Identity Federation pool the workflow uses. Read that directory for what it
creates; this document does not repeat it.

```bash
cd infra/gcp
tofu init -backend-config="bucket=roomlock-demo-tfstate"
tofu plan  -var project_id=roomlock-demo -var region=europe-central2
tofu apply -var project_id=roomlock-demo -var region=europe-central2
```

`tofu apply` is a deliberate step a human takes, and the deploy workflow never runs it: a deploy that
could also move a database or a Kafka cluster is not one anybody can undo by pointing at the previous
revision. Take the outputs into the variables of step 1.5.

The environment variables each revision is given come from here. They are the contract between that
directory and the `cloud` profiles; the profiles fail loudly rather than defaulting when one is
missing.

| Variable | Read by | What it is |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | all three | `cloud` |
| `PORT` | all three | set by Cloud Run; the ingress port of the revision (gRPC for payment-service) |
| `DB_HOST` | all three | private IP of the Cloud SQL instance |
| `DB_PORT` | all three | optional, `5432` |
| `DB_NAME` | all three | `booking`, `payment`, `notification` |
| `DB_USER` | all three | the user of that database |
| `DB_PASSWORD` | all three | **from Secret Manager**, exposed to the revision as an environment variable |
| `DB_POOL_SIZE` | all three | optional, `5`; instances × this has to stay under the instance limit |
| `KAFKA_BOOTSTRAP_SERVERS` | all three | bootstrap address of the Kafka cluster |
| `KAFKA_SECURITY_PROTOCOL` | all three | optional, `SASL_SSL` |
| `KAFKA_SASL_MECHANISM` | all three | optional, `OAUTHBEARER` |
| `KAFKA_SASL_JAAS_CONFIG` | all three | optional, the standard `OAuthBearerLoginModule required;` |
| `KAFKA_SASL_LOGIN_CALLBACK_HANDLER_CLASS` | all three | optional, `com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler` |
| `MANAGEMENT_PORT` | payment-service | optional, `8081`; the HTTP port that stays on localhost |
| `PAYMENT_GRPC_ADDRESS` | booking-service | `dns:///<payment-service host>:443` |
| `PAYMENT_AUDIENCE` | booking-service | `https://<payment-service host>` — the audience of the ID token |
| `OIDC_ISSUER_URI` | booking-service | the issuer whose access tokens this deployment trusts, exactly as it appears in the `iss` claim |
| `OIDC_JWK_SET_URI` | booking-service | where that issuer publishes its signing keys, e.g. `<issuer>/protocol/openid-connect/certs` for Keycloak or `<issuer>/discovery/v2.0/keys` for Entra ID |

The four Kafka variables have working defaults, so a deployment only sets them to authenticate
differently. `DB_PASSWORD`, `PAYMENT_GRPC_ADDRESS`, `PAYMENT_AUDIENCE`, `OIDC_ISSUER_URI` and
`OIDC_JWK_SET_URI` have none: a revision without them does not start, which is better than one that
starts and cannot charge — or, for the last two, one that starts and trusts the wrong provider.

**Bring your own identity provider.** booking-service is a resource server: it validates an RS256
access token against a JWK set and issues nothing. Which provider that is, is a deployment decision and
nothing in `infra/gcp` makes it — there is no Keycloak in the cloud profile, and the Keycloak of
`infra/docker-compose.yml` is a local convenience whose credentials are committed in a realm file. Any
OIDC provider works as long as it signs with RS256 and publishes a JWK set: Keycloak, Auth0, Okta,
Entra ID, Cloud Identity Platform. Two things it has to be set up to do: put a stable subject in the
token (a UUID subject becomes the guest id as it stands, anything else is mapped to one), and issue the
`catalog:write` scope to whoever may write the catalog and to nobody else. Both URIs are spelled out
rather than discovered from `<issuer>/.well-known/openid-configuration`, because the address the keys
are fetched from may be an internal one and a value that is written down cannot surprise anybody.
Neither is read before the first request that carries a token, so an unreachable issuer delays 401s
rather than the rollout — see [runbook](runbook.md#every-request-answers-401).

## 3. The deploy

**Actions → Deploy → Run workflow**, pick the environment, run it. There is no trigger on a tag: a tag
says which commit but never which project, and every name the deploy is pointed at comes from the
variables of a GitHub environment. The commit SHA is the image tag either way, so asking a human to
pick the target costs nothing.

What [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) does:

1. refuses to start if a variable of the environment is missing;
2. authenticates with `google-github-actions/auth` over Workload Identity Federation — no key;
3. builds the three images with Jib and pushes them straight into Artifact Registry as
   `<region>-docker.pkg.dev/<project>/<repo>/<service>:<commit sha>`, `linux/amd64`, authenticating
   with a one-hour access token of that identity rather than a `docker login`. Only the SHA is pushed:
   Cloud Run pins a revision to the digest it deployed, so a moving `latest` would never be what
   serves traffic and would only make "which commit is running" a question with two answers;
4. rolls payment-service, then notification-service, then booking-service onto the new image
   (payment-service first, because a booking that reaches the previous provider is only a charge that
   is asked for again), and **fails the run unless the revision that ends up serving is the one it
   created** — a rollout that quietly kept the old revision is not a green tick;
5. prints the revisions and the URLs in the run summary. Those URLs are what step 4 is run against.

A developer's local build is untouched by all of this: `./gradlew jibDockerBuild` still writes
`roomlock/<service>:dev` into the local Docker daemon, which is what the compose stack of the
[README](../README.md) expects.

## 4. Verification

This is the point of the exercise: the same path a local run takes, against the deployed system. Paste
it into a shell that has `gcloud`, `curl`, `jq` and a login with rights on the project.

```bash
PROJECT=roomlock-demo
REGION=europe-central2

BOOKING_URL="$(gcloud run services describe booking-service \
  --project "$PROJECT" --region "$REGION" --format 'value(status.url)')"

# Two tokens, two headers, and mixing them up is the first thing that goes wrong here.
#
# Cloud Run's IAM check reads X-Serverless-Authorization when it is present - that header exists
# precisely so that Authorization stays free for the application. If booking-service was deployed
# public (booking_service_public = true) this one is simply ignored.
RUN_AUTH="X-Serverless-Authorization: Bearer $(gcloud auth print-identity-token)"

# Authorization carries the guest's access token, which booking-service validates against
# OIDC_ISSUER_URI and which has nothing to do with Google. Client credentials here because a script
# has no browser; a real guest signs in and gets a token of their own. The client has to carry
# catalog:write, because the two requests that follow write the catalog.
# Which issuer the revision was actually pointed at, read from the revision rather than remembered.
OIDC_ISSUER_URI="$(gcloud run services describe booking-service \
  --project "$PROJECT" --region "$REGION" --format json \
  | jq -r '.spec.template.spec.containers[0].env[]
           | select(.name == "OIDC_ISSUER_URI") | .value')"

OIDC_CLIENT_ID=roomlock-operator
read -rsp 'client secret: ' OIDC_CLIENT_SECRET; echo

TOKEN_URL="$(curl -fsS "$OIDC_ISSUER_URI/.well-known/openid-configuration" | jq -r .token_endpoint)"
TOKEN="$(curl -fsS -X POST "$TOKEN_URL" \
  -d grant_type=client_credentials -d scope=catalog:write \
  -d client_id="$OIDC_CLIENT_ID" -d client_secret="$OIDC_CLIENT_SECRET" | jq -r .access_token)"

AUTH="Authorization: Bearer $TOKEN"
JSON='Content-Type: application/json'

# Nothing below works without a token, so find that out here rather than three steps later.
{ test -n "$TOKEN" && test "$TOKEN" != null; } \
  && echo "issuer $OIDC_ISSUER_URI, token acquired" \
  || echo 'NO TOKEN: check the client, its secret and whether it carries catalog:write'
```

A `401` from any request below means the token, not the deployment: expired, signed by an issuer the
revision was not pointed at, or refused because the revision cannot reach `OIDC_JWK_SET_URI`. A `403`
with an HTML body and no `urn:roomlock:problem:` type is Cloud Run's IAM check, not booking-service —
that is `X-Serverless-Authorization`. See [runbook](runbook.md#every-request-answers-401).

### The catalog

```bash
HOTEL_ID="$(curl -fsS -X POST "$BOOKING_URL/api/v1/hotels" \
  -H "$RUN_AUTH" -H "$AUTH" -H "$JSON" \
  -d '{"name":"RoomLock Demo","city":"Warsaw"}' | jq -r .id)"

ROOM_ID="$(curl -fsS -X POST "$BOOKING_URL/api/v1/hotels/$HOTEL_ID/rooms" \
  -H "$RUN_AUTH" -H "$AUTH" -H "$JSON" \
  -d '{"number":"101","capacity":2,"pricePerNight":"120.00"}' | jq -r .id)"

echo "hotel $HOTEL_ID room $ROOM_ID"
```

Two `201`s with ids prove the token was accepted with its scope, the HTTP layer, the Cloud SQL
connection over the private IP, and that Flyway migrated the `booking` database on startup — a service
that could not migrate would not have become healthy at all. A `403` here with a
`urn:roomlock:problem:forbidden` body is the one failure this step adds over the previous version of
it: the token is valid and the client does not carry `catalog:write`.

### Hold the room

```bash
KEY="demo-$(date +%s)"
CHECK_IN="$(date -u -d '+1 day' +%F 2>/dev/null || date -u -v+1d +%F)"
CHECK_OUT="$(date -u -d '+2 days' +%F 2>/dev/null || date -u -v+2d +%F)"
STAY="{\"roomId\":\"$ROOM_ID\",\"checkIn\":\"$CHECK_IN\",\"checkOut\":\"$CHECK_OUT\"}"

BOOKING_ID="$(curl -fsS -X POST "$BOOKING_URL/api/v1/bookings" \
  -H "$RUN_AUTH" -H "$AUTH" -H "$JSON" -H "Idempotency-Key: $KEY" \
  -d "$STAY" | jq -r .id)"

# The same request again, with the same key: a replay, not a second booking.
curl -fsS -X POST "$BOOKING_URL/api/v1/bookings" \
  -H "$RUN_AUTH" -H "$AUTH" -H "$JSON" -H "Idempotency-Key: $KEY" \
  -d "$STAY" | jq '{id, guestId, status}'
```

The first answer is `201` with `"status": "HELD"`. The second is **`200` with the same `id`** — the
idempotent hold, working in the cloud. A `409` here would mean the replay was read as a competing
request, which is the bug that idempotency exists to prevent.

Note what the body does *not* say: there is no `guestId` in it. The guest is the subject of the token,
and the `guestId` that comes back is that subject — adding the field to the request would be answered
`400` with the field named. The retry replays because the *same token* sent the *same key*: the
idempotency key is unique per guest, so the same key under a different token is a different request.

### Pay for it, then pay again

```bash
curl -fsS -X POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/pay" -H "$RUN_AUTH" -H "$AUTH" \
  | jq '{id, status, totalPrice}'

curl -fsS -X POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/pay" -H "$RUN_AUTH" -H "$AUTH" \
  | jq '{id, status, totalPrice}'
```

Both answer `200` with `"status": "CONFIRMED"` and `"totalPrice": "120.00"`. The first one is the
whole cross-service path: booking-service opened an authenticated gRPC channel to payment-service,
signed an ID token for its audience from the service account of the instance, and payment-service
approved 12000 grosze — under the 100000 threshold of its stubbed acquirer. **A `503`
`payment-unavailable` is the failure to investigate**: it means the charge got no answer, which on a
first deploy almost always means the ID token, the audience or the IAM binding, not the acquirer.

The second `200` is the idempotent charge. That the money was only taken once is proven below, in the
logs of payment-service — not by this answer, which is identical either way.

### Cancel the confirmed booking

```bash
curl -fsS -X POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/cancel" -H "$RUN_AUTH" -H "$AUTH" \
  | jq '{id, status, cancelReason}'

sleep 10
curl -fsS "$BOOKING_URL/api/v1/bookings/$BOOKING_ID" -H "$RUN_AUTH" -H "$AUTH" \
  | jq '{status, cancelReason}'
```

`200` with `"status": "CANCELLED"` and `"cancelReason": "USER_CANCELLED"`. Cancelling a *confirmed*
booking also writes a `RefundRequested` event to the outbox, so this one request is what proves the
whole asynchronous half: outbox sweep → Kafka → payment-service refunds. The `sleep` is for the
sweeps; the refund itself is checked in the logs.

### That somebody else's booking does not exist

```bash
# A second token, of a different subject. Any client of the same issuer will do; here the same client
# with a different scope would still be the same service account, so this needs a second identity -
# a guest account of the provider, or a second client.
# With only one identity available, skip this and read BookingOwnershipIntegrationTest instead.
curl -sS -o /dev/null -w '%{http_code}\n' \
  "$BOOKING_URL/api/v1/bookings/$BOOKING_ID" -H "$RUN_AUTH" -H "Authorization: Bearer $OTHER_TOKEN"
```

`404`. Not `403`: the answer to a booking somebody else owns is the same answer an id that was never
created gets, so nothing about it tells a caller whether the id is real. That is the whole point of
the choice, and [architecture](architecture.md#authentication-and-authorization) says why.

### That the events arrived

Nothing above can see Kafka. These two queries can:

```bash
# notification-service: one line per event it was told about, printed by its logging sender.
gcloud logging read \
  'resource.type="cloud_run_revision"
   AND resource.labels.service_name="notification-service"
   AND textPayload:"To guest"' \
  --project "$PROJECT" --freshness 30m --limit 20 --format 'value(textPayload)'
```

Expect three lines for this guest — `HOLD`, `CONFIRMATION`, `CANCELLATION`, in that order. They prove
that the outbox published to `roomlock.booking-events.v1`, that the consumer authenticated to the
managed broker with SASL/OAUTHBEARER, and that it claimed and wrote each notification. Nothing at all
here means the broker refused the client: look for `SaslAuthenticationException` in the same log.

```bash
# payment-service: what it decided, and what it refunded.
gcloud logging read \
  'resource.type="cloud_run_revision"
   AND resource.labels.service_name="payment-service"
   AND (textPayload:"Charge of booking" OR textPayload:"Refunded payment")' \
  --project "$PROJECT" --freshness 30m --limit 20 --format 'value(textPayload)'
```

Expect **exactly one** `Charge of booking <id> is APPROVED` for this booking — two would mean the
guest was charged twice, which is the failure the idempotent charge exists to prevent — and one
`Refunded payment <payment id> of booking <id>`, which only exists if the `RefundRequested` event
crossed `roomlock.refund-requests.v1`. Note that this service has no reachable HTTP port, so there is
nothing to curl: its logs and its probe are how it is observed.

### The metrics

Managed Prometheus answers PromQL over the monitoring API, so the counters of a local Grafana are
readable from a shell:

```bash
promql() {
  curl -fsS -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    "https://monitoring.googleapis.com/v1/projects/$PROJECT/location/global/prometheus/api/v1/query" \
    --data-urlencode "query=$1" | jq -c '.data.result'
}

promql 'sum by (strategy, outcome) (roomlock_hold_outcomes_total)'
promql 'sum by (outcome) (roomlock_payment_charges_total)'
promql 'sum by (outcome) (roomlock_payment_refunds_total)'
promql 'sum(roomlock_outbox_published_total)'
promql 'sum(roomlock_outbox_unpublished)'
promql 'sum(roomlock_outbox_stuck)'
```

What proves what:

- `roomlock_hold_outcomes_total{strategy="constraint", outcome="inserted"}` at 1, and every other
  outcome at 0. The replayed hold is deliberately absent here: it never reaches a strategy, because the
  idempotency key is looked up before one is asked.
- `roomlock_payment_charges_total{outcome="approved"}` at 1 **with `{outcome="replayed"}` at 1** — the
  charge that happened once and the second request that only read it back.
- `roomlock_payment_refunds_total{outcome="refunded"}` at 1: the refund crossed Kafka and was applied.
- `roomlock_outbox_published_total` at 4 — held, confirmed, cancelled, and the refund the
  cancellation of a *confirmed* booking adds next to it — and
  **`roomlock_outbox_unpublished` back at 0**, which is the single number that says the outbox is not
  falling behind. `roomlock_outbox_stuck` at 0 says nothing is being refused over and over.

notification-service reports no metrics of its own — the log query above is how the end of the chain is
checked. An empty result from *every* query means the sidecar is not scraping: check that
`/actuator/prometheus` is in the exposure list (it is, in every profile) and that the sidecar is part of
the revision.

The Grafana dashboard of the local stack selects on `service="..."`. Managed Prometheus does not label
a Cloud Run target that way — it labels it by `namespace` — and the OpenTofu adds a relabeling rule
that restamps `service` so the dashboard keeps working. That rule is unverified: **if a panel comes up
empty, rewrite its `service=` selector as `namespace=`.**

## 5. Teardown

The demonstration is meant to be destroyed as soon as step 4 has passed. The Kafka cluster and the
Cloud SQL instance are billed for existing, not for being used, so leaving them idle costs what
leaving them busy costs.

```bash
cd infra/gcp
tofu destroy -var project_id=roomlock-demo -var region=europe-central2
```

That takes the revisions, the databases, the cluster and the network with it. What it does not take:
the project, the APIs and the state bucket of step 1, because a human created those by hand. Delete
the project as well if nothing else lives in it — `gcloud projects delete roomlock-demo` is the one
move that guarantees nothing keeps billing:

```bash
gcloud artifacts repositories list --project roomlock-demo   # images outlive a destroy if kept
gcloud projects delete roomlock-demo
```

## 6. Rolling back a deploy

If step 4 fails, the previous revision is still there — Cloud Run keeps them, and a rollback is a
traffic change rather than a build. Nothing has to be rebuilt and nothing has to be pushed:

```bash
gcloud run revisions list --service booking-service \
  --project "$PROJECT" --region "$REGION" --format 'table(name, active, creationTimestamp)'

gcloud run services update-traffic booking-service \
  --project "$PROJECT" --region "$REGION" --to-revisions <previous revision>=100
```

Roll back in the reverse order of the deploy — booking-service first, then notification-service, then
payment-service — so the caller is never newer than the provider it charges against. Then run step 4
again: a rollback that is not verified is a hope.

What a rollback does **not** undo is a migration. Flyway runs on startup of every revision and only
goes forwards, so the previous revision has to be able to live with the schema the new one migrated to.
Every migration of this repository so far only adds, which is why the `cloud` profile keeps migrating
on startup at all (the reasoning is in the profile, next to `spring.flyway.enabled`). A migration that
drops or renames a column would break that, and would have to be run as its own deliberate step before
the deploy instead.

## After a deploy: what to watch

For the few minutes the demonstration lives:

| Watch | Where | Why |
| --- | --- | --- |
| `roomlock_outbox_unpublished` | Managed Prometheus | the one gauge that has to come back to 0; a rising one means events are stuck behind a broker or a credential |
| `roomlock_outbox_stuck` | Managed Prometheus | an event that was refused often enough to be a bug, not an outage |
| `roomlock_payment_charges_total{outcome="declined"}` vs `approved` | Managed Prometheus | a wall of declines is the acquirer's threshold, a wall of 503s is the ID token |
| `SaslAuthenticationException`, `UNAUTHENTICATED`, `DEADLINE_EXCEEDED` | Cloud Logging, all three services | the three failures a first deploy actually produces: the broker, the ID token, the gRPC hop |
| Startup probe failures | Cloud Run, per revision | a revision that cannot reach Cloud SQL never becomes ready, and the deploy fails loudly rather than silently serving the old one |
| `To guest` lines of notification-service | Cloud Logging | the end of the chain; if holds are counted and no guest is told, the events are not crossing |
| Cost | Billing | the cluster and the instance bill by the hour of existing — see step 5 |
