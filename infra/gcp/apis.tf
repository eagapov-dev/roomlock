// Service APIs this configuration needs. serviceusage and cloudresourcemanager are NOT here:
// enabling an API is itself a Service Usage call, so those two have to be on before the first
// `tofu apply` can run at all (README.md, bootstrap).
//
// disable_on_destroy is false everywhere. Turning an API off on destroy is a project-wide act that
// would break anything else in the project that happens to use it, and it makes a re-apply after a
// destroy slow and flaky.
locals {
  services = [
    "compute.googleapis.com",              // VPC, subnet, firewall, the PSA address
    "servicenetworking.googleapis.com",    // the private connection Cloud SQL's private IP is served over
    "sqladmin.googleapis.com",             // Cloud SQL
    "managedkafka.googleapis.com",         // Managed Service for Apache Kafka
    "run.googleapis.com",                  // Cloud Run
    "artifactregistry.googleapis.com",     // the Docker repository the three images live in
    "secretmanager.googleapis.com",        // the database password and the collector configs
    "monitoring.googleapis.com",           // Managed Prometheus ingests through the Monitoring API
    "logging.googleapis.com",              // the collector sidecar writes its own logs
    "iam.googleapis.com",                  // service accounts
    "iamcredentials.googleapis.com",       // Workload Identity Federation token exchange
    "sts.googleapis.com",                  // the STS endpoint GitHub's OIDC token is exchanged at
    "storage.googleapis.com",              // the GCS backend this state lives in
    "cloudresourcemanager.googleapis.com", // project-level IAM bindings are set through it
  ]
}

resource "google_project_service" "required" {
  for_each = toset(local.services)

  project = var.project_id
  service = each.value

  disable_on_destroy = false
}
