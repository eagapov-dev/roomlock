// One service account per service, and no shared identity. The Kafka ACLs in kafka.tf are written
// against these three principals, so "notification-service may read booking events and nothing else"
// is only true because the three have separate identities.
//
// No service account key is created anywhere in this directory, and none should ever be. CI
// authenticates through Workload Identity Federation (workload-identity.tf); a human authenticates
// with `gcloud auth application-default login`.

resource "google_service_account" "booking" {
  account_id   = "roomlock-${var.environment}-booking"
  display_name = "RoomLock booking-service (${var.environment})"
  description  = "Runtime identity of booking-service: produces both contract topics, calls payment-service over gRPC, owns the booking database."
  project      = var.project_id

  depends_on = [google_project_service.required]
}

resource "google_service_account" "payment" {
  account_id   = "roomlock-${var.environment}-payment"
  display_name = "RoomLock payment-service (${var.environment})"
  description  = "Runtime identity of payment-service: consumes refund requests, serves Charge and Refund over gRPC, owns the payment database."
  project      = var.project_id

  depends_on = [google_project_service.required]
}

resource "google_service_account" "notification" {
  account_id   = "roomlock-${var.environment}-notification"
  display_name = "RoomLock notification-service (${var.environment})"
  description  = "Runtime identity of notification-service: consumes booking events, owns the notification database."
  project      = var.project_id

  depends_on = [google_project_service.required]
}

locals {
  service_accounts = {
    booking      = google_service_account.booking
    payment      = google_service_account.payment
    notification = google_service_account.notification
  }

  service_account_members = {
    for k, sa in local.service_accounts : k => "serviceAccount:${sa.email}"
  }

  // Roles every runtime identity needs, for the same reason in each case.
  //
  // managedkafka.client  — grants managedkafka.clusters.connect, which is the *only* thing IAM
  //                        decides about Kafka. What each principal may then do to a topic or a
  //                        consumer group is decided by the ACLs in kafka.tf. The provider has no
  //                        google_managed_kafka_cluster_iam_member, so this cannot be scoped below
  //                        the project; the ACLs are what make it least privilege in practice.
  //
  // cloudsql.client      — NOT exercised by the current deployment. SPRING_DATASOURCE_URL points at
  //                        the instance's private IP over Direct VPC egress (run.tf), which is plain
  //                        TCP and needs no IAM at all. This is the role the Cloud SQL Java connector
  //                        and the auth proxy need, and it is granted so that switching the JDBC URL
  //                        to the connector form is a configuration change and not also an IAM
  //                        change. Drop it if you would rather the grant match the traffic exactly.
  //
  // monitoring.metricWriter,
  // logging.logWriter    — the Managed Prometheus collector sidecar runs under the same identity as
  //                        the service it scrapes and writes the samples through the Monitoring API.
  //                        Without metricWriter the sidecar starts and silently ingests nothing.
  runtime_roles = [
    "roles/managedkafka.client",
    "roles/cloudsql.client",
    "roles/monitoring.metricWriter",
    "roles/logging.logWriter",
  ]

  // Flattened so one google_project_iam_member covers every (service, role) pair.
  runtime_role_bindings = {
    for pair in setproduct(keys(local.service_accounts), local.runtime_roles) :
    "${pair[0]}:${pair[1]}" => { service = pair[0], role = pair[1] }
  }
}

resource "google_project_iam_member" "runtime" {
  for_each = local.runtime_role_bindings

  project = var.project_id
  role    = each.value.role
  member  = local.service_account_members[each.value.service]
}

// ---------------------------------------------------------------------------
// Secret access — per secret, per service account
// ---------------------------------------------------------------------------

// secretAccessor is granted on the secret, not on the project, so each service can read exactly the
// secrets it mounts and nothing else that ever lands in Secret Manager.
resource "google_secret_manager_secret_iam_member" "sql_password" {
  for_each = local.service_accounts

  project   = var.project_id
  secret_id = google_secret_manager_secret.sql_password.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = local.service_account_members[each.key]
}

// Each service reads only its own collector config, not the other two.
resource "google_secret_manager_secret_iam_member" "gmp_config" {
  for_each = local.service_accounts

  project   = var.project_id
  secret_id = google_secret_manager_secret.gmp_config[each.key].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = local.service_account_members[each.key]
}

// ---------------------------------------------------------------------------
// Who may invoke what
// ---------------------------------------------------------------------------

// payment-service: reached by booking-service and by nobody else. This is the binding that makes
// INGRESS_TRAFFIC_INTERNAL_ONLY meaningful — internal-only keeps the internet out, and this keeps
// everything else inside the project out too. allUsers must never appear here: Charge and Refund move
// money and the service authenticates nothing itself.
resource "google_cloud_run_v2_service_iam_member" "payment_invoker_booking" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.payment.name
  role     = "roles/run.invoker"
  member   = local.service_account_members.booking
}

// notification-service gets no invoker binding at all. Nothing calls it: it is a Kafka consumer, and
// its HTTP port exists only for the actuator endpoints, which the collector sidecar reaches over
// localhost inside the instance rather than through ingress. An absent binding is the point.

// booking-service: the only service a client talks to.
//
// var.booking_service_public defaults to false, which is the whole reason this is a variable. The
// service has no authentication of its own — POST /api/v1/bookings holds a room for whoever asks —
// so an allUsers invoker binding is a bookable API open to the internet, and that is not something to
// inherit from a default. With the default, a caller must present a Google ID token and be named in
// var.booking_invoker_members.
resource "google_cloud_run_v2_service_iam_member" "booking_invoker_public" {
  count = var.booking_service_public ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.booking.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "booking_invoker_restricted" {
  for_each = var.booking_service_public ? toset([]) : toset(var.booking_invoker_members)

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.booking.name
  role     = "roles/run.invoker"
  member   = each.value
}
