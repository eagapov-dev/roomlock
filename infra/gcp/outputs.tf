// What the rest of the world needs from this directory: the deploy workflow needs the registry and
// the federation identifiers, the application-side configuration needs the addresses, and a human
// verifying the deployment needs to know where to point curl.
//
// No output exposes the database password. It is in Secret Manager and in the state file, and adding
// it here would also put it in every `tofu output` and CI log that runs one.

// ---------------------------------------------------------------------------
// Service URLs
// ---------------------------------------------------------------------------

output "booking_service_url" {
  description = "Public entry point of the system: POST /api/v1/bookings and the rest of /api/v1 live here. Unless booking_service_public is true, a caller must send an Authorization: Bearer <ID token> minted for this exact URL as the audience."
  value       = google_cloud_run_v2_service.booking.uri
}

output "payment_service_url" {
  description = "Internal-only. This is also the ID token AUDIENCE booking-service must mint a token for when it calls Charge or Refund — a token for any other audience is rejected."
  value       = google_cloud_run_v2_service.payment.uri
}

output "notification_service_url" {
  description = "Internal-only and invokable by nobody. Published for completeness; there is no caller."
  value       = google_cloud_run_v2_service.notification.uri
}

// ---------------------------------------------------------------------------
// The values the application-side configuration has to match
// ---------------------------------------------------------------------------

output "service_environment" {
  description = "The environment each Cloud Run service is actually deployed with, keyed by service. Handy for checking that a property override landed under the name the application expects."
  value = {
    booking = merge(local.common_env, {
      SPRING_DATASOURCE_URL                                = local.jdbc_urls.booking
      SPRING_GRPC_CLIENT_CHANNELS_PAYMENT_ADDRESS          = "static://${replace(google_cloud_run_v2_service.payment.uri, "https://", "")}:443"
      SPRING_GRPC_CLIENT_CHANNELS_PAYMENT_NEGOTIATION_TYPE = "tls"
    })
    payment      = merge(local.common_env, { SPRING_DATASOURCE_URL = local.jdbc_urls.payment })
    notification = merge(local.common_env, { SPRING_DATASOURCE_URL = local.jdbc_urls.notification })
  }
}

output "kafka_bootstrap_address" {
  description = "SPRING_KAFKA_BOOTSTRAP_SERVERS. Reachable only from inside the VPC. Managed Kafka requires SASL_SSL/OAUTHBEARER rather than the PLAINTEXT of the local broker — see the application-side note in README.md, which is another agent's work, not this directory's."
  value       = google_managed_kafka_cluster.main.bootstrap_address
}

output "kafka_topics" {
  description = "The four topics that exist: the two of the contract and the two dead-letter topics, with their partition counts."
  value = {
    for name, topic in google_managed_kafka_topic.contract :
    name => { partitions = topic.partition_count, replication_factor = topic.replication_factor }
  }
}

output "sql_instance_connection_name" {
  description = "project:region:instance. Needed by the Cloud SQL auth proxy, which is how a human reaches this instance at all — it has no public IP."
  value       = google_sql_database_instance.main.connection_name
}

output "sql_private_ip_address" {
  description = "The address the JDBC URLs point at. Routable only from inside the VPC."
  value       = google_sql_database_instance.main.private_ip_address
}

output "sql_password_secret_id" {
  description = "Secret Manager secret holding the application user's password. Read it with `gcloud secrets versions access latest --secret=<this>`; it is deliberately not an output itself."
  value       = google_secret_manager_secret.sql_password.secret_id
}

// ---------------------------------------------------------------------------
// What the deploy workflow needs
// ---------------------------------------------------------------------------

output "artifact_registry_repository" {
  description = "Image prefix. A service image is <this>/<service>:<tag>, e.g. push booking-service here and set image_tag to the same value."
  value       = local.image_repository
}

output "docker_login_registry" {
  description = "The host to configure Docker against: `gcloud auth configure-docker <this>`."
  value       = "${var.region}-docker.pkg.dev"
}

output "workload_identity_provider" {
  description = "Full resource name for google-github-actions/auth's workload_identity_provider input."
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "deploy_service_account_email" {
  description = "Service account the workflow impersonates, for google-github-actions/auth's service_account input. No key exists for it."
  value       = google_service_account.deploy.email
}

output "runtime_service_account_emails" {
  description = "The three runtime identities, which are also the principals the Kafka ACLs are written against."
  value       = { for k, sa in local.service_accounts : k => sa.email }
}

// ---------------------------------------------------------------------------
// Teardown
// ---------------------------------------------------------------------------

output "teardown_command" {
  description = "How to stop the bill. Everything in this configuration is billed for as long as it exists."
  value       = var.deletion_protection ? "deletion_protection is true: set it to false and apply before destroying, or `tofu destroy` will fail on Cloud SQL, Kafka and the Cloud Run services." : "tofu destroy -var project_id=${var.project_id}"
}
