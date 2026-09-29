// Secret Manager holds the one thing in this deployment that must not sit in an environment
// variable: the database password. Everything else the services read — the JDBC URL, the Kafka
// bootstrap address, the payment-service address — is an address, not a credential, and an address
// in an env var is readable by design.
//
// Cloud Run resolves a secret_key_ref at instance start, so the value never appears in the service's
// description, in `gcloud run services describe`, or in a deploy log. An env var would appear in all
// three.
//
// The password is generated here rather than supplied. That keeps it out of variables, out of
// terraform.tfvars and out of anyone's shell history. It does land in the state file, which is why
// the state bucket is private and versioned (state.tf) — that is the standard trade and the reason
// the bucket is not something to make public.

resource "random_password" "sql_app" {
  length = 32

  // Cloud SQL accepts these, and leaving out the shell-hostile punctuation means the value can be
  // pasted into a psql invocation during an incident without quoting games.
  special          = true
  override_special = "-_.~"
  min_lower        = 4
  min_upper        = 4
  min_numeric      = 4
  min_special      = 2
}

resource "google_secret_manager_secret" "sql_password" {
  secret_id = "roomlock-${var.environment}-db-password"
  project   = var.project_id
  labels    = var.labels

  replication {
    // Automatic replication: the secret is read once per instance start, from one region, so there
    // is nothing to gain from pinning replicas and a regional outage would only make it worse.
    auto {}
  }

  // A deleted secret cannot be recovered, and deleting this one bricks every running revision.
  deletion_protection = var.deletion_protection

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "sql_password" {
  secret      = google_secret_manager_secret.sql_password.id
  secret_data = random_password.sql_app.result
}
