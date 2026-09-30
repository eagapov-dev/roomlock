# RoomLock on Google Cloud

OpenTofu/Terraform definition of the deployment: **Cloud SQL for PostgreSQL 16**, **Google Managed
Service for Apache Kafka**, and the three services on **Cloud Run**. The architecture is not
substituted for anything cheaper — Kafka stays Kafka, not Pub/Sub, because
`dev.roomlock.contract.v1` is Kafka records with a key, a header and a per-partition ordering
guarantee, and both consumers are Spring Kafka listener containers with a
`DeadLetterPublishingRecoverer`.

This is a mirror of `infra/docker-compose.yml`, not a new design: one Postgres instance with three
databases, one Kafka cluster with the two contract topics plus their `.DLT` companions, three
services.

> **This deployment is a one-off.** It is meant to come up, be verified, and be destroyed the same
> day. Read the next section before applying it.

---

## What this costs while it exists, and how to make it stop

Nothing here scales to zero. Three of these line items bill **per hour, merely by existing**, whether
or not a single booking is ever made:

| What | Why it bills continuously | Pricing |
| --- | --- | --- |
| **Managed Kafka cluster** — the big one | Provisioned capacity: 3 vCPU, 3 GiB, and **100 GiB of disk per broker**. There is no idle tier and no scale to zero. Billed on vCPU-hours, memory, and disk. | [Managed Kafka pricing](https://cloud.google.com/managed-service-for-apache-kafka/pricing) |
| **Cloud SQL instance** | A running instance is billed per hour for vCPU/memory plus provisioned storage, plus backup storage. Stopping it pauses compute but not storage. | [Cloud SQL pricing](https://cloud.google.com/sql/pricing) |
| **Three always-on Cloud Run instances** | `min_instance_count = 1` and `cpu_idle = false` on each service, so each bills CPU and memory 24/7 rather than per request. This is not tuning — see [Why the instances are always on](#why-the-instances-are-always-on). The Managed Prometheus collector sidecar is a second always-on container in each instance, so it is **six** billed containers, not three. | [Cloud Run pricing](https://cloud.google.com/run/pricing) |

Smaller, but not zero:

| What | Note | Pricing |
| --- | --- | --- |
| Managed Prometheus samples | Billed per sample ingested. `gmp_scrape_interval` is 30s rather than the local stack's 5s, partly for this and partly because `roomlock.outbox.unpublished` runs a `COUNT` on `outbox_event` on every scrape. | [Cloud Monitoring pricing](https://cloud.google.com/stackdriver/pricing) |
| Artifact Registry storage | Per GB above a free allowance. Three JRE-based images are not tiny. | [Artifact Registry pricing](https://cloud.google.com/artifact-registry/pricing) |
| Secret Manager | Per active secret version, plus access operations. Four secrets. | [Secret Manager pricing](https://cloud.google.com/secret-manager/pricing) |
| Cloud Logging | Per GB ingested above a free allowance. | [Cloud Logging pricing](https://cloud.google.com/stackdriver/pricing) |
| The state bucket | A few KB of Standard storage. | [Cloud Storage pricing](https://cloud.google.com/storage/pricing) |

**No figures are quoted here on purpose.** Prices vary by region and change; read the pages above for
your region rather than trusting a number in a README.

### Make it stop

```sh
tofu destroy
```

`deletion_protection` defaults to **false** precisely so this succeeds in one pass. Destroy
**immediately after the verification run** — the Kafka cluster alone does not become cheaper by being
forgotten about.

Two settings that would otherwise leave billed orphans behind are pinned to follow
`deletion_protection`, so a default destroy leaves nothing:

- `settings.retain_backups_on_delete` — would keep the automated backups after the instance is gone.
- `settings.final_backup_config.enabled` — Cloud SQL otherwise takes a *final* backup on delete and
  keeps it.

### Which variables shrink the bill

| Variable | Effect |
| --- | --- |
| `kafka_vcpu_count`, `kafka_memory_gib`, `kafka_broker_disk_size_gib` | Already at the floor the API enforces: 3 vCPU, 1 GiB per vCPU, 100 GiB disk per broker. **There is no smaller Managed Kafka cluster to ask for.** |
| `sql_tier` | `db-g1-small` by default. `db-f1-micro` is the floor but has 0.6 GiB — too small for three ten-connection Hikari pools. `db-custom-1-3840` is the smallest tier with SLA coverage. |
| `sql_availability_type` | `ZONAL`. `REGIONAL` adds a synchronous standby and roughly doubles the instance cost. |
| `sql_point_in_time_recovery` | `false`. Enabling it archives write-ahead logs as billed storage and restarts the instance. |
| `max_instance_count` | `1`. Also a correctness cap, not only a cost one — see below. |
| `service_cpu`, `service_memory` | `1` / `1Gi`. 512Mi is Cloud Run's floor at 1 CPU, but the images run with `-XX:MaxRAMPercentage=75`, so that is a 384 MB heap for a Spring Boot service running Hibernate, Flyway and a Kafka client. |
| `gmp_scrape_interval` | Longer interval, fewer billed samples. |

### What survives a destroy

A reviewer does not need the infrastructure running to see the work. What is free or near-free to keep:

- **The Terraform in this directory**, in the repository. This is the deliverable.
- **Artifact Registry images**, if you keep the repository — but it is *near*-free, not free: storage
  is billed per GB above a free allowance. `tofu destroy` deletes the repository and the images with
  it, which is the clean default.
- **Cloud Logging entries** already ingested, within the free retention allowance.
- **Cloud Monitoring metrics** already ingested — the Managed Prometheus samples outlive the services
  that produced them, so a dashboard screenshot or a PromQL query still works after teardown.
- **Screenshots** of the Grafana dashboard or the Cloud Monitoring metrics explorer, taken during the
  verification run. Cheapest evidence there is.

What must be destroyed, because it bills by existing: the Kafka cluster, the Cloud SQL instance, the
three Cloud Run services. Everything in the table at the top of this section.

---

## Bootstrap — what a human runs before the first apply

Four things cannot be Terraformed by the configuration that needs them.

**1. A project with billing attached.**

```sh
gcloud projects create roomlock-demo-123456
gcloud billing projects link roomlock-demo-123456 --billing-account=XXXXXX-XXXXXX-XXXXXX
gcloud config set project roomlock-demo-123456
```

**2. Two APIs, by hand.** Enabling an API is itself a Service Usage call, so these two have to be on
before `apis.tf` can enable the rest:

```sh
gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com
```

**3. The state bucket.** Deliberately *not* a resource in this configuration: a bucket that holds its
own state cannot be destroyed by the run reading from it, and `tofu destroy` has to finish in one
pass. It outlives every apply and destroy.

```sh
gcloud storage buckets create gs://roomlock-tfstate-roomlock-demo-123456 \
  --location=europe-west1 \
  --uniform-bucket-level-access \
  --public-access-prevention

# Versioning, so a corrupted or half-written state can be rolled back.
gcloud storage buckets update gs://roomlock-tfstate-roomlock-demo-123456 --versioning
```

**4. The first init and apply.** The backend takes the bucket name at init time, because a `backend`
block cannot read a variable:

```sh
cd infra/gcp
cp terraform.tfvars.example terraform.tfvars   # then set project_id

gcloud auth application-default login

tofu init -backend-config="bucket=roomlock-tfstate-roomlock-demo-123456"
tofu plan
tofu apply
```

The first apply takes a while: a Managed Kafka cluster and a Cloud SQL instance are each on the order
of ten to twenty minutes, and the private services connection has to exist before the SQL instance
can ask for a private address.

**5. Push the images.** The services will not start until the three images exist at the tag
`image_tag` names:

```sh
gcloud auth configure-docker "$(tofu output -raw docker_login_registry)"
REPO="$(tofu output -raw artifact_registry_repository)"

./gradlew jibDockerBuild
for s in booking-service payment-service notification-service; do
  docker tag "roomlock/$s:dev" "$REPO/$s:dev"
  docker push "$REPO/$s:dev"
done
```

---

## Layout

| File | What is in it |
| --- | --- |
| `versions.tf` | Pinned provider, the GCS backend. |
| `variables.tf` | Every input, with the floors and trade-offs in the descriptions. |
| `apis.tf` | The service APIs, and which two are bootstrap instead. |
| `network.tf` | VPC, subnet, Private Service Access, the one firewall rule. Why there is no NAT. |
| `sql.tf` | PostgreSQL 16, three databases, one user, private IP only. |
| `kafka.tf` | Cluster, the four topics, the ACLs that make each service's access least-privilege. |
| `registry.tf` | The Docker repository and the image path. |
| `secrets.tf` | The generated database password. |
| `observability.tf` | The Managed Prometheus collector configs. |
| `run.tf` | The three services. The interesting file. |
| `iam.tf` | Runtime service accounts, their roles, and who may invoke what. |
| `workload-identity.tf` | GitHub Actions federation and the deploy service account. |
| `outputs.tf` | Everything the deploy workflow, the application config and a human need. |

---

## The decisions worth knowing

### Why the instances are always on

`min_instance_count = 1` and `cpu_idle = false` on all three services. This is the reason this is not
a default Cloud Run deployment, and it is a correctness requirement rather than a performance one.

Cloud Run's default throttles CPU to near zero outside a request. That breaks three things:

- **booking-service** runs two `@Scheduled` sweeps: `OutboxScheduler` every `PT1S` publishes the
  transactional outbox, `HoldExpiryScheduler` every `PT30S` cancels expired holds. Neither is a
  request. Under the default they would only run while a client happened to be calling, so a
  committed outbox row would never be sent and an abandoned hold would block its room well past
  `roomlock.hold.ttl`. The whole reliability argument in `docs/reliability.md` rests on those sweeps
  running.
- **payment-service** runs `RefundRequestsListener`, a Kafka listener container. A listener receives a
  record only while its consumer is polling.
- **notification-service** has no HTTP API a client uses at all. If it is not running, nothing polls
  `roomlock.booking-events.v1` and no guest is ever notified — and nothing will ever wake it, because
  its trigger is a Kafka record, not an HTTP request.

A request-driven service can afford to scale to zero. A scheduler or a consumer cannot.

`max_instance_count` is capped at **1**, but as a cost default rather than a correctness one: with
`cpu_idle = false` every instance bills for CPU it never yields. A second replica would be safe —
the outbox publisher takes a lease so one instance sweeps at a time, and the hold expiry and payment
reconciliation sweeps claim their batches with `FOR UPDATE SKIP LOCKED` (`docs/reliability.md`).

### Ingress and who may invoke what

| Service | Ingress | Invoker |
| --- | --- | --- |
| booking-service | `INGRESS_TRAFFIC_ALL` | `var.booking_invoker_members`, or `allUsers` only if `booking_service_public = true` |
| payment-service | `INGRESS_TRAFFIC_INTERNAL_ONLY` | booking-service's service account, and nothing else |
| notification-service | `INGRESS_TRAFFIC_INTERNAL_ONLY` | **nobody** — no binding exists |

`booking_service_public` defaults to **false**. booking-service authenticates nothing itself —
`POST /api/v1/bookings` holds a room for whoever asks — so an `allUsers` invoker binding is a bookable
API on the open internet, and that is not something to inherit from a default. While it is false:

```sh
curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
     -H "Idempotency-Key: $(uuidgen)" \
     -X POST "$(tofu output -raw booking_service_url)/api/v1/bookings" ...
```

payment-service needs **both** halves. Internal-only keeps the internet out; the single `run.invoker`
binding keeps every *other* workload in the project out. Internal-only alone would admit anything in
the VPC, and `Charge` and `Refund` move money.

### gRPC between two Cloud Run services

payment-service listens on two ports — HTTP 8081 (`server.port`) and gRPC 9091
(`spring.grpc.server.port`) — and **a Cloud Run service routes ingress to exactly one container
port**. 9091 wins, because gRPC is the only edge anything crosses to reach this service. Nothing is
lost: `/actuator/prometheus` on 8081 is scraped by the collector sidecar over localhost inside the
instance, and the startup probe is pointed at 8081 explicitly.

`ports { name = "h2c" }` is what makes it work. Cloud Run terminates TLS at its frontend and forwards
to the container as HTTP/2 cleartext, giving HTTP/2 end to end. With the default (`http1`) the
frontend downgrades the connection and every gRPC call fails.

The caller therefore speaks TLS to port 443, which is why `run.tf` sets both
`SPRING_GRPC_CLIENT_CHANNELS_PAYMENT_ADDRESS` (to `static://<host>:443`) and
`SPRING_GRPC_CLIENT_CHANNELS_PAYMENT_NEGOTIATION_TYPE` (to `tls`), overriding the `plaintext` of a
local run.

**Authentication is application-side work.** Every call needs
`Authorization: Bearer <ID token>` whose token was minted for an **audience equal to
payment-service's own URL** — an access token, or a token for any other audience, is rejected. On
Cloud Run the token comes from the metadata server:

```
GET http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=<callee URL>
Metadata-Flavor: Google
```

Attaching it to the channel is a `CallCredentials` on the channel built by `PaymentChannelConfig`.
The audience to use is published as the `payment_service_url` output.

### Private networking

- **Cloud SQL has no public IP.** `ipv4_enabled = false`, and the only path to it is the VPC peering
  `google_service_networking_connection` establishes. The services reach it at its private address
  over Direct VPC egress: plain TCP, no proxy, no unix socket, no `socketFactory` — which matters,
  because a `socketFactory` would need a dependency added to the Gradle build, and the build is not
  this directory's to change. A human reaches it through the Cloud SQL auth proxy.
- **The Kafka cluster is private.** Broker and bootstrap addresses are allocated in the same subnet
  the Cloud Run revisions egress from. `public_cluster_config` is deliberately unset.
- **There is no Cloud NAT.** Nothing in RoomLock calls out to the internet, and
  `vpc_access.egress = PRIVATE_RANGES_ONLY` means only RFC 1918 destinations are routed into the VPC.
  Secret Manager and Monitoring are reached over Cloud Run's own egress path, so no NAT gateway and no
  external address is needed anywhere.
- **One firewall rule.** A custom-mode VPC has no rules and the implied rule denies all ingress. The
  Kafka brokers hold addresses *in this subnet*, so reaching them is ordinary VPC ingress and needs an
  explicit allow on tcp:9092. Cloud SQL does not, because its address is on the far side of the
  peering.

### Least privilege

Each service has its own service account. The Kafka ACLs in `kafka.tf` are written against those three
principals, which is the only reason "notification-service may read booking events and nothing else" is
true.

Managed Kafka authorises in **two stages**: IAM decides whether a principal may *connect*
(`roles/managedkafka.client`), and then ordinary Kafka ACLs decide what it may do. **The provider has
no `google_managed_kafka_cluster_iam_member`**, so the connect grant cannot be scoped below the
project — the per-topic and per-consumer-group ACLs are what make it least privilege in practice.

`roles/cloudsql.client` is granted but **not exercised**: the JDBC URLs use the private IP, which needs
no IAM. It is the role the Cloud SQL Java connector needs, granted so that switching the URL form later
is a configuration change and not also an IAM change. Drop it if you would rather the grant match the
traffic exactly.

No service account key is created anywhere, and none should be. CI uses Workload Identity Federation,
with an `attribute_condition` pinning the provider to one repository *and* a `principalSet` binding
keyed on the same repository — two independent checks, because this is the boundary between the public
internet and an account that can deploy. The deploy account gets `artifactregistry.writer` and
`run.developer` (not `run.admin`, which carries `setIamPolicy` and could grant `allUsers` the invoker
role on booking-service), plus `iam.serviceAccountUser` on each of the three runtime accounts
individually rather than project-wide.

---

## Observability: what changes for the Grafana dashboard

**Prometheus scraping does not exist on Cloud Run.** There is no `static_configs` list to point at
three container names the way `infra/prometheus.yml` does, because a Cloud Run instance has no stable
address and nothing outside it may reach a port that is not the ingress port.

Google Managed Prometheus inverts it: a **collector sidecar** runs inside every instance, scrapes the
application container over localhost, and pushes samples to the Monitoring API. So the three actuator
endpoints stay private and are still collected.

There is **no Terraform resource for this**. The provider has no `google_monitoring_run_monitoring` or
equivalent; the entire contract is "run this image with this YAML at `/etc/rungmp/config.yaml`". So
`observability.tf` authors a `RunMonitoring` document per service as a Secret Manager secret, and
`run.tf` mounts it as a volume. That is the supported mechanism, not a workaround.

### The label, precisely

`infra/grafana/dashboards/roomlock.json` filters every panel on `service="booking-service"` (and the
other two). That label comes from `infra/prometheus.yml`, which attaches it to its static targets. **It
does not exist under Managed Prometheus.** GMP writes to the `prometheus_target` monitored resource,
whose labels are:

| Label | Value under GMP |
| --- | --- |
| `project_id` | the project |
| `location` | the region |
| `cluster` | the literal `__run__` |
| **`namespace`** | **the Cloud Run service name** — this is what identifies the service |
| `job`, `instance` | from the scrape config and the instance |

So the native answer is: **`service="booking-service"` becomes `namespace="booking-service"`.**

Rather than let every panel silently return no data, `observability.tf` adds a `metricRelabeling` rule
to each scrape config that stamps a literal `service` label with the same value the local stack uses.
`metricRelabeling` is spelled singular because `RunMonitoring` reuses the `ScrapeEndpoint` fields of
GMP's `PodMonitoring` CRD. **This is the one piece of this directory that cannot be checked without
applying it** — if it does not take effect, the fallback is mechanical: rewrite the dashboard's
`service=` selectors to `namespace=`.

Two other things change for the dashboard, neither of them a label:

- **The datasource.** Grafana must read from the Cloud Monitoring Prometheus API frontend instead of a
  scraping Prometheus. `infra/grafana/provisioning/datasources/prometheus.yml` points at the local
  container and is unchanged by this directory.
- **Metric names.** Through the GMP frontend, metrics carry a `prometheus.googleapis.com/` prefix and a
  type suffix at the Cloud Monitoring API level. The Prometheus-compatible query frontend presents
  them under their original names, so `roomlock_outbox_unpublished` still resolves — but a query typed
  against the Monitoring API directly will not look like the dashboard's.

`infra/grafana/**` and `infra/prometheus*.yml` are untouched. Nothing about the local stack changes.

---

## Application-side configuration this deployment assumes

Owned by another agent, listed here because the deployment does not work without it.

**Managed Kafka does not accept `PLAINTEXT`.** The local broker does; this one requires SASL_SSL with
OAUTHBEARER over Application Default Credentials. That needs a dependency —
`com.google.cloud.hosted.kafka:managed-kafka-auth-login-handler` — and these properties:

```yaml
spring:
  kafka:
    security:
      protocol: SASL_SSL
    properties:
      sasl.mechanism: OAUTHBEARER
      sasl.login.callback.handler.class: com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler
      sasl.jaas.config: org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;
```

Without them every producer and consumer fails to connect, and `SPRING_KAFKA_BOOTSTRAP_SERVERS` being
correct will not help.

The gRPC ID token (above) is the other half. `Profiles.CLOUD` now carries a `cloud` document in every
service's `application.yml`
(`docs/architecture.md`, known gaps), and a `cloud` profile document is the natural home for both.

The environment variables this directory sets are exactly the property names already in the three
`application.yml` files, overridden through Spring's relaxed binding, the same way
`infra/docker-compose.yml` does it. `tofu output service_environment` prints what each service actually
received.

---

## What has been verified, and what has not

**Verified:**

- `tofu fmt -check` passes.
- `tofu validate` passes against the pinned provider, `hashicorp/google` **8.4.0**.
- Every resource type and every argument was checked against that provider version's own schema
  (`tofu providers schema -json`) and its generated documentation — not from memory.

**Not verified, because it needs GCP credentials:**

- `tofu plan` and `tofu apply` were **never run**. There are no credentials in this environment, so
  `validate` is the ceiling. Everything below is therefore reasoned from documentation and unproven in
  practice:
  - that the Managed Kafka cluster accepts this capacity/subnet combination and that the quota exists;
  - that `replication_factor = 3` is satisfiable on a minimum 3-vCPU cluster;
  - that the Kafka ACL set is sufficient — in particular that a producer needs nothing beyond topic
    `WRITE`/`DESCRIBE` plus cluster `DESCRIBE`, and that the idempotent-producer path does not also
    want `IDEMPOTENT_WRITE`;
  - that the `metricRelabeling` rule actually stamps the `service` label (see above for the fallback);
  - that the `h2c` port plus TLS-to-443 gRPC path works end to end, which also depends on
    application-side code this directory does not own;
  - that `db-g1-small` carries three services' Hikari pools comfortably;
  - the startup probe windows, which are guesses at how long Flyway takes against a cold instance.

**Known provider gaps, stated rather than worked around:**

- **No resource for Managed Prometheus on Cloud Run.** Hence the Secret Manager + sidecar approach.
- **No `google_managed_kafka_cluster_iam_member`.** `roles/managedkafka.client` is therefore
  project-scoped, narrowed by ACLs.
- **No dead-letter-topic argument on `google_managed_kafka_topic`.** Kafka has no such concept and
  neither does the API; `<topic>.DLT` is an ordinary topic, which is exactly how
  `DeadLetterPublishingRecoverer` addresses it. All four topics are declared identically.
- **`google_sql_database` has no `OWNER`.** A database created through the API is owned by
  `cloudsqlsuperuser`, which `google_sql_user` is a member of — which is also what lets
  `db/constraint/V4` run `CREATE EXTENSION IF NOT EXISTS btree_gist`, since only a member of that role
  may create an extension on Cloud SQL.
