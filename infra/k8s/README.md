# RoomLock on Kubernetes

Plain manifests plus **Kustomize** overlays for the whole system: PostgreSQL 16 with three databases,
Kafka in KRaft mode, the three services, and a Prometheus that scrapes them. The same topology as
`infra/docker-compose.yml` and `infra/gcp/`, in the third of the three shapes this repository keeps
side by side.

> ### These manifests have never been applied to a running cluster.
>
> Nothing here has been `kubectl apply`-ed, no `kind` cluster was created, no image was loaded, no pod
> was ever scheduled. Everything below was authored against the compose stack, the Terraform
> definition and each service's `application.yml`, and validated **statically** — YAML parse, a
> Kustomize render, and schema validation of the rendered output against the Kubernetes 1.30 API.
> [What was validated, and what could not be](#what-was-validated-and-what-was-not) says exactly what
> that does and does not prove. Expect the first run to need a fix; the sections that are most likely
> to be it are flagged in [Known gaps](#known-gaps).

**No Helm, deliberately.** A chart would put this same content behind `{{ .Values.booking.replicas }}`
and a `_helpers.tpl`, and the mechanics these manifests exist to show — a probe pointed at a real
actuator group, the lease that makes a second replica safe, a `NetworkPolicy` that names one caller —
would be visible only after rendering the chart. Kustomize patches the objects instead of generating
them, so `base/` reads as Kubernetes and the overlay reads as a list of local differences.

---

## Layout

```
infra/k8s/
├── base/
│   ├── kustomization.yaml            the object list, and the namespace everything lands in
│   ├── namespace.yaml                + Pod Security Admission at `restricted`
│   ├── serviceaccounts.yaml          one identity per service, mirroring infra/gcp/iam.tf
│   ├── secret.yaml                   placeholder credentials, and where a real one comes from
│   ├── postgres.yaml                 StatefulSet + PVC + headless Service + the init script
│   ├── kafka.yaml                    StatefulSet + PVC + headless Service, KRaft, 3 partitions
│   ├── booking-service.yaml          ConfigMap + Deployment (2 replicas) + Service
│   ├── payment-service.yaml          ConfigMap + Deployment + Service (gRPC 9091, HTTP 8081)
│   ├── notification-service.yaml     ConfigMap + Deployment + Service (nobody calls it)
│   ├── ingress.yaml                  booking-service only
│   ├── network-policies.yaml         who may talk to whom
│   ├── pod-disruption-budgets.yaml   what a drain may take away
│   └── prometheus.yaml               scrape by pod annotation, + the relabel the dashboard needs
└── overlays/kind/
    ├── kustomization.yaml            resource sizes, replica counts, ingress class for one laptop
    └── kind-cluster.yaml             the cluster itself: port mappings, and the CNI caveat
```

**One file per component, and cross-cutting concerns in their own files.** A `Deployment`, its
`Service` and its `ConfigMap` belong together — they change together and they are read together. The
`Ingress`, the `NetworkPolicy` objects and the `PodDisruptionBudget` objects deliberately do *not*
live inside the service files: they are statements about the whole deployment, one per file, so
"who is reachable" and "what may be taken away" can each be read in one sitting. Every *why* is a
comment in the manifest; this README covers the running of it and the comparison.

---

## Bringing it up on `kind`, from a standing start

Requires JDK 21, Docker, `kubectl`, and [`kind`](https://kind.sigs.k8s.io/) (`brew install kind`).

### 1. Build the images

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # or wherever JDK 21 lives
./gradlew jibDockerBuild
```

Jib writes `roomlock/booking-service:dev`, `roomlock/payment-service:dev` and
`roomlock/notification-service:dev` into the local Docker daemon — the same three images the compose
stack uses, built by `build-logic/src/main/kotlin/roomlock.spring-boot-service.gradle.kts`. A
different repository or tag is `-Pimage.repository=... -Pimage.tag=...`, and then the `image:` line of
each Deployment has to be patched to match; this shape has no registry, so the defaults are the path
of least resistance.

### 2. Create the cluster

```bash
kind create cluster --config infra/k8s/overlays/kind/kind-cluster.yaml
```

That config exists for one reason: an `Ingress` needs host ports forwarded into the node and the
`ingress-ready=true` label, and both are fixed when the node is created. A plain
`kind create cluster --name roomlock` works for everything else.

### 3. Load the images into the node

```bash
for s in booking payment notification; do
  kind load docker-image "roomlock/$s-service:dev" --name roomlock
done
```

**This step has no equivalent in the other two shapes and it is the one people forget.** A kind node
is a container with its own image store; it cannot see the Docker daemon that built the images, and
there is no registry in this deployment to pull from. Skip it and every pod sits in
`ErrImageNeverPull`/`ImagePullBackOff` — `imagePullPolicy: IfNotPresent` is what stops the kubelet
from trying to fetch `roomlock/booking-service:dev` from Docker Hub, where it does not exist.

Re-run it after every rebuild. `kind load` does not restart anything, so a new image also needs
`kubectl rollout restart deployment/<name> -n roomlock`.

### 4. Apply the overlay

```bash
kubectl apply -k infra/k8s/overlays/kind
kubectl get pods -n roomlock -w
```

Expect crash-loops for the first minute or two, and expect them to stop. Kubernetes has no
`depends_on`: all of it is created at once, the three services start before PostgreSQL has finished
`initdb`, and their Flyway migrations fail against a database that is not there. The `startupProbe`
budget (30 × 5s) and the kubelet's restart backoff are what turn that into "ready eventually" — where
the compose stack spells it out with `depends_on: condition: service_healthy`.

```bash
kubectl rollout status deployment/booking-service -n roomlock --timeout=5m
```

### 5. Reach booking-service

```bash
kubectl port-forward -n roomlock svc/booking-service 8090:8080
```

Port 8090 on purpose: the same host port the compose stack publishes, so every URL in the root
[README](../../README.md) and in `load-tests/` is unchanged —
`http://localhost:8090/swagger-ui.html`, `/actuator/health`, `/actuator/prometheus`.

The `Ingress` is the alternative, and it is optional. It needs a controller, which a stock kind
cluster does not have:

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes-sigs/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl wait -n ingress-nginx --for=condition=ready pod \
  -l app.kubernetes.io/component=controller --timeout=180s
# then, through the node's forwarded port 80 → 8088:
curl -fsS http://localhost:8088/actuator/health
```

### Tear down

```bash
kind delete cluster --name roomlock
```

`kubectl delete -k infra/k8s/overlays/kind` removes the objects but **leaves the
PersistentVolumeClaims**, which is deliberate on Kubernetes' part and surprising once: the database
survives the deployment being deleted. `kubectl delete pvc -n roomlock --all` is the equivalent of
`docker compose down -v`.

---

## The smoke test

The same flow the other two shapes document — `docs/deployment.md` against Cloud Run, the root README
against compose — with no `Authorization` header, because nothing in this shape authenticates a
caller. With the port-forward of step 5 running, in another shell:

```bash
BOOKING_URL=http://localhost:8090
JSON='Content-Type: application/json'
```

### The catalog

```bash
HOTEL_ID="$(curl -fsS -X POST "$BOOKING_URL/api/v1/hotels" -H "$JSON" \
  -d '{"name":"RoomLock Demo","city":"Warsaw"}' | jq -r .id)"

ROOM_ID="$(curl -fsS -X POST "$BOOKING_URL/api/v1/hotels/$HOTEL_ID/rooms" -H "$JSON" \
  -d '{"number":"101","capacity":2,"pricePerNight":"120.00"}' | jq -r .id)"

echo "hotel $HOTEL_ID room $ROOM_ID"
```

Two `201`s prove the HTTP layer, the connection to `postgres:5432`, and that Flyway migrated the
`booking` database — a pod that could not migrate would never have passed its startup probe.

### Hold the room, twice with the same key

```bash
GUEST_ID="$(uuidgen | tr 'A-Z' 'a-z')"
KEY="demo-$(date +%s)"
CHECK_IN="$(date -u -d '+1 day' +%F 2>/dev/null || date -u -v+1d +%F)"
CHECK_OUT="$(date -u -d '+2 days' +%F 2>/dev/null || date -u -v+2d +%F)"

BOOKING_ID="$(curl -fsS -X POST "$BOOKING_URL/api/v1/bookings" \
  -H "$JSON" -H "Idempotency-Key: $KEY" \
  -d "{\"roomId\":\"$ROOM_ID\",\"guestId\":\"$GUEST_ID\",\"checkIn\":\"$CHECK_IN\",\"checkOut\":\"$CHECK_OUT\"}" \
  | jq -r .id)"

curl -fsS -X POST "$BOOKING_URL/api/v1/bookings" \
  -H "$JSON" -H "Idempotency-Key: $KEY" \
  -d "{\"roomId\":\"$ROOM_ID\",\"guestId\":\"$GUEST_ID\",\"checkIn\":\"$CHECK_IN\",\"checkOut\":\"$CHECK_OUT\"}" \
  | jq '{id, status}'
```

`201` with `"status": "HELD"`, then **`200` with the same `id`**. A `409` would mean the replay was
read as a competing request.

Note what the port-forward does to this test: it targets the `Service`, so the two requests may well
land on **different booking-service pods**. That the second one is still a replay and not a second
booking is a property of the database (the idempotency record), not of sticky routing — which is
exactly the thing two replicas are supposed to prove.

### Pay, then pay again

```bash
curl -fsS -X POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/pay" | jq '{id, status, totalPrice}'
curl -fsS -X POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/pay" | jq '{id, status, totalPrice}'
```

Both `200`, `"status": "CONFIRMED"`, `"totalPrice": "120.00"`. The first is the cross-service path:
booking-service opened a plaintext gRPC channel to `payment-service:9091` and the stubbed acquirer
approved 12000 grosze, under its 100000 threshold. A `503 payment-unavailable` here is the failure to
investigate — in this shape it means the channel, the `NetworkPolicy` or the pod, never a token, since
there is no authentication to get wrong.

### Cancel it, and watch the asynchronous half

```bash
curl -fsS -X POST "$BOOKING_URL/api/v1/bookings/$BOOKING_ID/cancel" | jq '{id, status, cancelReason}'
sleep 10
curl -fsS "$BOOKING_URL/api/v1/bookings/$BOOKING_ID" | jq '{status, cancelReason}'
```

Cancelling a *confirmed* booking writes both `BookingCancelled` and `RefundRequested` to the outbox, so
this one request drives outbox sweep → Kafka → payment-service refund → notification. The `sleep` is
for the sweeps.

### That the events actually arrived

Nothing above can see Kafka. These can:

```bash
# notification-service printed a line per event it was told about.
kubectl logs -n roomlock -l app.kubernetes.io/name=notification-service --tail=50 | grep 'To guest'

# payment-service handled the refund.
kubectl logs -n roomlock -l app.kubernetes.io/name=payment-service --tail=50

# The topics exist, with three partitions each - including the .DLT companions.
kubectl exec -n roomlock kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

`kubectl exec` rather than a client on the laptop: there is no external Kafka listener in this shape,
because a broker cannot advertise an address that is only true inside a `kubectl port-forward` process.

### That only one booking-service pod publishes the outbox

This is the claim that makes `replicas: 2` legitimate, and it is directly observable:

```bash
kubectl exec -n roomlock postgres-0 -- \
  psql -U postgres -d booking -c 'SELECT holder, acquired_at, expires_at FROM outbox_lease'

kubectl get pods -n roomlock -l app.kubernetes.io/name=booking-service \
  -o custom-columns=NAME:.metadata.name,IP:.status.podIP
```

One row, one holder, renewed every second. Delete the pod that holds it and the other takes over
within `roomlock.outbox.lease` (30s) — during which nothing is published and nothing is lost, because
the rows are still in `outbox_event`.

---

## Metrics, and keeping the existing Grafana dashboard working

```bash
kubectl port-forward -n roomlock svc/prometheus 9090:9090
# http://localhost:9090/targets - three services, two booking-service pods
```

**Scraping is by pod annotation, not a `ServiceMonitor`.** A `ServiceMonitor` is a custom resource
owned by the Prometheus Operator: without that operator the CRD does not exist, so the API server
rejects the object outright and `kubeconform` cannot validate it either. Installing an operator so
that three scrape targets can be declared is not a trade this deployment makes at three services. The
mechanism is visible in `base/prometheus.yaml` instead of inside a controller — pod discovery
restricted to this namespace, `prometheus.io/scrape|port|path` annotations on each pod template, and
five relabel rules.

**The `service` label is the thing to be careful about.** `infra/grafana/dashboards/roomlock.json`
selects every panel on `service="..."` and its template variable is `label_values(service)`. That
label exists in the compose stack only because `infra/prometheus.yml` attaches it by hand to three
static targets. Under Kubernetes service discovery it does not exist at all: a target arrives with
`job` (`roomlock`), `instance` (`10.244.0.7:8080`) and a pile of `__meta_kubernetes_*` labels that are
dropped unless something copies them. One relabel rule copies `app.kubernetes.io/name` into `service`,
and **that line is what keeps the dashboard working unchanged**:

```yaml
- source_labels: [__meta_kubernetes_pod_label_app_kubernetes_io_name]
  action: replace
  target_label: service
```

Two consequences worth knowing before reading a panel:

- A second label, `pod`, now exists, and **every series appears once per replica**. Panels that `sum
  by (...)` are unaffected; the raw gauges are not. `roomlock_outbox_unpublished` is each pod's own
  `COUNT` over the same table, so with two booking-service pods it draws two lines that sit on top of
  each other — and two lines that *diverge* would mean the pods are looking at different databases.
- `hikaricp_connections_*` is now per pod, so the pool size in the dashboard is the pool of one
  replica and the load on PostgreSQL is that times the replica count. `maximum-pool-size` is 10 in the
  default profile; two booking-service pods plus two of each other service is 60 connections against a
  `postgres:16-alpine` default of 100.

**No Grafana in this shape**, and the reason is a Kustomize rule rather than a preference: a
`ConfigMap` can only be generated from files inside the kustomization root, so mounting
`infra/grafana/dashboards/roomlock.json` would mean either a second copy of the dashboard to keep in
step or `--load-restrictor LoadRestrictionsNone` on every build. Point a Grafana at the port-forwarded
Prometheus and import that file, or read the queries in `/graph`.

---

## The three shapes

| | `infra/docker-compose.yml` | `infra/k8s/` | `infra/gcp/` |
| --- | --- | --- | --- |
| **What it is** | One machine, one bridge network, everything wired by container name. | Declarative desired state in a cluster: a controller's job is to make the world match these files. | Managed services and serverless containers; IAM is the security boundary. |
| **What it gives you** | The whole system in two commands, and a debugger attached to it. Logs, ports and volumes are all one `docker` away. | Self-healing (a failed probe restarts a container, a lost pod is rescheduled), rolling updates with a health gate, horizontal replicas, and network and admission policy the platform enforces. A pod that just runs — no fight to keep a consumer alive. | Someone else runs PostgreSQL and Kafka, with backups, patching and failover. No node to own, no CNI to choose, request-scoped billing everywhere but the parts pinned on. |
| **What it costs** | Nothing survives the laptop. One replica of everything, no restarts you did not type, no notion of a rollout. Says nothing about how the system behaves when a process dies. | You now operate a cluster. The database and the broker are `StatefulSet`s you are responsible for — no backups, no failover, no operator. Six moving pieces before the first request (build, load, apply, probe, port-forward, policy), and a `NetworkPolicy` that only means something if the CNI enforces it. | Real money per hour, for a Kafka cluster and a Cloud SQL instance that bill by existing. Scale-to-zero is unusable here (`min_instance_count = 1`, `cpu_idle = false` on all three), so the serverless saving is the first thing the architecture gives up. Project-level setup: APIs, IAM, VPC, Workload Identity. |
| **Pick it when** | You are writing code, running the load tests, or want the whole thing on a plane. | You want to see or rehearse the operational shape — replicas, rollouts, probes, disruption, policy — or you already have a cluster and this is where services go. | You need it to exist for other people, with someone else on call for the stateful parts. |
| **The always-on question** | Does not arise: containers run. | Does not arise: pods run. The three `@Scheduled` sweeps and both consumers work because a process that is started stays started. | **The central decision.** `cpu_idle = false` and `min_instance_count = 1` on every service, because a throttled instance stops polling Kafka and stops running sweeps, and notification-service has no HTTP API that could ever wake it. Two settings, fighting a platform whose unit is a request. |
| **Who may call payment-service** | Anything on the compose network. | booking-service, by `NetworkPolicy`, per packet, by pod label. | booking-service's service account, by a single `run.invoker` binding, per request, by signed identity — plus internal-only ingress. |
| **Secrets** | Literals in `.env`, never anything real. | A `Secret` object; base64 is an encoding, and a real cluster projects the value in from a secret manager (External Secrets, CSI driver). | Secret Manager, resolved into the revision at start; the value is not in `gcloud run services describe`. |
| **Scaling a consumer** | Not a thing you do. | `kubectl scale`, up to the partition count, and no further usefully. | `max_instance_count`, up to the partition count, and no further usefully. Same ceiling, different lever. |

---

## What a cluster can say that the other two cannot

Each of these is a deliberate choice; the reasoning is in the manifests, in full.

**One `Ingress`, for booking-service, and nothing for the other two.** It is the only service a client
talks to. `Ingress` rather than Gateway API — Gateway is the better design (typed roles instead of
vendor annotations, delegable route attachment) and it is also CRDs plus a controller plus a
GatewayClass, so on a cluster without them the manifest is *rejected*, not merely inert.
`networking.k8s.io/v1` is built in, which means this object validates against a plain kind cluster and
a reader can apply it without two prerequisites first. See `base/ingress.yaml`.

**`NetworkPolicy`: payment-service accepts gRPC from booking-service, notification-service accepts
nothing.** The network equivalent of the internal-only ingress plus IAM binding that
`infra/gcp/run.tf` gets. The one exception is the scrape port, open to the Prometheus pod only — the
same exception the Cloud Run shape makes with a collector sidecar on localhost. PostgreSQL and Kafka
get the same treatment: the three services, nothing else. **On a stock kind cluster these are probably
decoration** — see [Known gaps](#known-gaps). There are no egress rules, and `base/network-policies.yaml`
says why not.

**Replica counts, and the guarantee behind them.** booking-service runs **two**, and that is a claim
about correctness, not about throughput:

- The outbox publisher takes a lease. `OutboxPublisher.publishBatch()` asks
  `PublisherLease.heldBy(...)` first and returns 0 if another instance holds it — one row, upserted
  with a conditional `ON CONFLICT` (same holder, or the lease has expired), renewed every sweep,
  `roomlock.outbox.lease` = 30s against a 1s sweep. One instance publishes; the other skips. That is
  what preserves the per-booking ordering the contract promises.
- The hold-expiry sweep and the payment reconciliation sweep claim their batches with `FOR UPDATE SKIP
  LOCKED` inside a CTE (`BookingRepository.expireHolds`, and the `PAYING` lease that re-stamps
  `payment_started_at`). Two instances take disjoint batches, neither waits, and the conditional
  `WHERE status = ...` of each `UPDATE` is the real guard.
- **What the lease does not promise**, and its own javadoc says so: it *bounds* the window in which
  two instances can both publish, it does not close it. An instance that stalls past its lease expiry
  — a long GC, a paused node — may still be inside a send when the next one takes over, and that
  duplicate reaches the topic. It is survivable and not invisible: every event carries an id and both
  consumers deduplicate on it. Exactly-once is not on offer. Ten replicas buy more of that window, not
  less.

payment-service and notification-service run two in `base/` and one in the kind overlay (a laptop).
A second replica of payment-service is *not* a second charge handler:
`static://payment-service:9091` plus a ClusterIP is balanced per TCP connection, and gRPC keeps one
connection open, so every `Charge` lands on whichever pod the channel reached. It is refund capacity
and a warm standby. Balancing the calls needs a headless Service and a `dns:///` target with a
round-robin policy — an application change, not a manifest change.

**Consumer parallelism is capped by partitions.** `KAFKA_NUM_PARTITIONS: 3`, matching the local
broker. Three partitions means at most three members of a consumer group ever receive anything: a
**fourth replica of payment-service or notification-service joins the group, is assigned nothing and
idles**, costing a JVM and a connection pool for no throughput. If notifications are behind, the fix
is partitions (`kafka-topics.sh --alter --partitions`), not pods — and re-partitioning changes which
partition a key lands on, so per-key ordering only holds for keys written afterwards. This is the same
ceiling `max_instance_count` runs into on Cloud Run, reached with a different lever.

**`PodDisruptionBudget`, and what it is not.** It constrains *voluntary* disruption only — `kubectl
drain`, an autoscaler compacting nodes, a node pool upgrade. It has no opinion about a node that dies,
an OOM kill or a failed probe, and it does not govern rollouts (`strategy.rollingUpdate` does).
`maxUnavailable: 1` rather than `minAvailable: 1` on all three: identical at two replicas, and not
identical later — at five replicas `minAvailable: 1` would permit four simultaneous evictions, and at
one replica it would make `kubectl drain` block forever. **Nothing for PostgreSQL and nothing for
Kafka**: one replica each, no replication, so draining that node stops the system until the pod
returns with its volume. No budget can fix that; replication or a managed service can, which is most
of the price difference with `infra/gcp/`.

**Pod Security Admission at `restricted`.** Built in, not an add-on: the API server refuses a
non-compliant pod at creation and names the rule. Every workload here already complies — the three
services as uid 1000, PostgreSQL as 70, Kafka as 1000, Prometheus as 65534, all with capabilities
dropped, no privilege escalation and a RuntimeDefault seccomp profile. There is no equivalent in the
other two shapes: compose has no admission layer, and Cloud Run's sandbox makes the question moot.

---

## What was validated, and what was not

Run from the repository root. `kind`, `helm` and the Prometheus Operator were not used at any point.

| Check | Command | Result |
| --- | --- | --- |
| Every file is YAML | `python3 -c "import yaml,sys; list(yaml.safe_load_all(open(sys.argv[1])))" <file>` | 15/15 files parse |
| The overlay renders | `kubectl kustomize infra/k8s/overlays/kind` | 34 objects, exit 0 (`base/` renders the same 34) |
| The rendered objects are valid Kubernetes 1.30 | `kubectl kustomize infra/k8s/overlays/kind \| kubeconform -strict -summary -kubernetes-version 1.30.0 -` | 34 valid, 0 invalid, 0 skipped |
| Each manifest is valid on its own | `ls infra/k8s/base/*.yaml \| grep -v kustomization \| xargs kubeconform -strict -summary -kubernetes-version 1.30.0` | 34 resources in 12 files, all valid |
| Patches landed where intended | reading the rendered output | replicas, ingress class, resource sizes, Kafka heap, PVC sizes, namespace rewriting of the ClusterRoleBinding subject |
| Probe and Service port names resolve | script over the rendered output | every named `targetPort` and every named probe port exists as a container port |
| Pod Security `restricted` | script over the rendered output | all 6 pod specs satisfy every `restricted` rule |

**`kubectl apply --dry-run=client -f` cannot be run without a cluster, and that is not a
configuration problem.** Client-side dry-run still builds a REST mapper from the API server's
discovery document, so with no cluster reachable it fails before it looks at the file
(`couldn't get current server API group list: dial tcp [::1]:8080: connect: connection refused`), and
`--validate=false` only removes the schema check, not the discovery call. `kubeconform` is the
offline substitute and is the stricter of the two about schemas: it validates every field of every
object against the published 1.30 OpenAPI schema, with `-strict` rejecting unknown fields — which is
most of what a client dry-run checks. What it cannot see is anything the API server decides:
admission (including the `restricted` level above, checked by hand instead), defaulting, immutability,
and whether a referenced object exists.

**Everything else is unverified.** Nothing below has been observed, only reasoned about:

- That any pod starts, becomes ready, or passes a probe. The actuator paths, the probe groups and the
  budgets are read from each `application.yml`, not from a response.
- That PostgreSQL initialises as uid 70 with an `fsGroup`-owned volume and runs the init script from a
  `ConfigMap` — the most likely single point of failure in this directory, and the one everything else
  waits on.
- That the Kafka broker formats its storage with the pinned `CLUSTER_ID` and advertises a listener the
  clients can reach, and that the topics are auto-created with three partitions.
- That `readOnlyRootFilesystem: true` plus an `emptyDir` at `/tmp` is enough for these Spring Boot
  images.
- That the `NetworkPolicy` objects permit what the system needs and refuse what it does not — on any
  CNI, let alone kindnet.
- That the Prometheus relabel rules produce the `service` label the dashboard needs.
- Every resource number. The requests and limits are estimates for a JVM of this shape, not
  measurements.

The Java build is untouched by this work: `./gradlew spotlessCheck` passes, and nothing outside
`infra/k8s/` was changed.

---

## Known gaps

**This is a demonstration cluster.** Everything below is a deliberate limit of a deployment meant to be
read and run once on a laptop, not a list of things to fix before a release.

1. **It has never been applied.** Said once more here, because it belongs in this list.

2. **`NetworkPolicy` is probably not enforced.** kindnet, kind's default CNI, has historically not
   implemented NetworkPolicy at all: the objects are accepted, listed and ignored, so payment-service
   answers anything that asks. To see the policies work, create the cluster with
   `disableDefaultCNI: true` (commented in `kind-cluster.yaml`) and install Calico or Cilium before
   anything else. This is the sharpest example of a manifest being intent that another component has
   to honour — and worth testing rather than trusting, on any cluster.

3. **`StatefulSet` PostgreSQL is not how to run PostgreSQL.** One instance, no replication, no
   failover, no backup, no point-in-time recovery; a lost node is a lost database. In a real cluster
   this file would be a CloudNativePG `Cluster` (or Zalando's operator, or an external managed
   instance) — `infra/gcp/sql.tf` buys the managed answer. It is here so the topology is complete.

4. **`StatefulSet` Kafka is not how to run Kafka.** One broker means replication factor 1 on every
   topic, so booking-service's `acks: all` is satisfied by a single disk and the durability the
   contract assumes is not really there. A second replica cannot be had by raising `replicas`: each
   broker needs its own node id and a voter list, which a plain StatefulSet's environment cannot
   express per pod. That is what Strimzi exists for; `infra/gcp/kafka.tf` buys the managed answer.

5. **Topics are auto-created.** As in the compose stack: the first produce or subscribe creates
   `roomlock.booking-events.v1`, `roomlock.refund-requests.v1` and their `.DLT` companions with
   `KAFKA_NUM_PARTITIONS: 3` and replication factor 1. A real deployment creates them deliberately,
   with a replication factor and a retention policy, before the services start — and would not leave
   auto-creation on.

6. **The Secret is a placeholder.** The values are the throwaway credentials of `infra/.env.example`.
   `base64` is an encoding, `kubectl get secret -o yaml` prints it, and at rest it is only as
   encrypted as the cluster's etcd. A real deployment does not apply that file at all: the password
   arrives from a secret manager through External Secrets Operator or the Secrets Store CSI driver.
   Neither is expressible as a plain manifest, which is why the gap is documented rather than closed.

7. **The PostgreSQL init script is a copy.** `base/postgres.yaml` inlines
   `infra/postgres/init/01-create-databases.sh` because a `ConfigMap` cannot be generated from a file
   outside the kustomization root without `--load-restrictor LoadRestrictionsNone`. Two copies, and a
   change to one has to be made to the other.

8. **`ConfigMap`s are literal, so a config change does not roll anything.** A `configMapGenerator`
   would append a content hash to the name and restart the pods automatically. The trade was made for
   readability — the comments explaining each variable can live in a manifest and cannot live in
   `kustomization.yaml`. Edit and then
   `kubectl rollout restart deployment/<name> -n roomlock`.

9. **No autoscaling.** No `HorizontalPodAutoscaler`, for two reasons: a kind cluster has no
   metrics-server, so one would sit `<unknown>/80%` forever; and the interesting ceiling here is not
   CPU but the partition count, which an HPA does not know about. For booking-service — the one
   Deployment that could usefully scale on load — a fixed count makes the lease argument easier to
   watch work.

10. **No TLS anywhere.** No cert-manager, no `tls:` block on the Ingress, plaintext gRPC between
    booking-service and payment-service, `PLAINTEXT` to Kafka, and no `sslmode` on the JDBC URLs. The
    `cloud` profile of each service has the TLS story (SASL_SSL to Managed Kafka, TLS to a gRPC
    endpoint on 443), and it is deliberately not activated here: it is written for Cloud Run, where
    every one of those statements is true and in a cluster none of them are.

11. **Single-node by default, so two of the protections cannot be demonstrated.** The
    PodDisruptionBudgets only constrain evictions during a drain, and there is nowhere to drain to; the
    pod anti-affinity is `preferred`, so both booking-service replicas land on the one node and the
    preference is simply unsatisfied. `kind-cluster.yaml` says where to add workers.

12. **No `ResourceQuota`, no `LimitRange`, no `PriorityClass`.** A shared cluster would want all three
    — a quota so this namespace cannot eat a node, a LimitRange so a pod added later cannot omit its
    requests, and priorities so PostgreSQL is not the pod evicted under pressure. Omitted as cluster
    policy rather than application deployment, and because a single-tenant kind cluster has nobody to
    protect.

13. **Prometheus keeps its data in an `emptyDir`, and there is no Grafana.** A restart of the
    Prometheus pod loses the history; the dashboard has to be imported into a Grafana pointed at the
    port-forwarded Prometheus (see above for why it is not in this directory).

14. **Nothing here is in CI.** `.github/` is not this directory's to change, so no workflow renders
    the overlay or validates it. The commands in
    [What was validated](#what-was-validated-and-what-was-not) are the manual equivalent, and
    `kubeconform` against `kubectl kustomize` output is what a CI job would run.
