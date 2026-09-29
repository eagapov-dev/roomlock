// One PostgreSQL 16 instance with the three databases of infra/postgres/init/01-create-databases.sh,
// owned by one application user. This mirrors the local topology on purpose: each service runs
// Flyway against its own database and nothing reads another service's (docs/architecture.md, data
// ownership), and one instance is what keeps the bill to one instance.

locals {
  // The three databases, in the order 01-create-databases.sh creates them.
  databases = ["booking", "payment", "notification"]

  sql_app_user = "roomlock"
}

resource "google_sql_database_instance" "main" {
  name    = "roomlock-${var.environment}"
  project = var.project_id
  region  = var.region

  database_version = "POSTGRES_16"

  // Terraform-side guard. The API-side guard is settings.deletion_protection_enabled below; both
  // move together so one variable is enough to make an environment throwaway.
  deletion_protection = var.deletion_protection

  settings {
    tier              = var.sql_tier
    availability_type = var.sql_availability_type
    edition           = "ENTERPRISE"

    disk_type             = "PD_SSD"
    disk_size             = var.sql_disk_size_gb
    disk_autoresize       = true
    disk_autoresize_limit = 0 // 0 means no ceiling; the instance grows rather than filling up

    deletion_protection_enabled = var.deletion_protection

    ip_configuration {
      // No public IP, ever. The only path to this instance is the VPC peering established in
      // network.tf, which is why the services need Direct VPC egress to reach it at all. A public
      // IP on a database holding bookings and payments is not a default worth having.
      ipv4_enabled    = false
      private_network = google_compute_network.main.id

      // Reject an unencrypted connection. The JDBC driver negotiates TLS by default, so this costs
      // the application nothing.
      ssl_mode = "ENCRYPTED_ONLY"

      // Pin the private address to the range network.tf reserved, rather than letting the service
      // pick any range it finds free in the peering.
      allocated_ip_range = google_compute_global_address.private_service_access.name
    }

    // Two separate settings decide whether deleting this instance leaves billed storage behind, and
    // both default the wrong way for a throwaway. They are pinned explicitly so `tofu destroy`
    // leaves nothing:
    //
    //   retain_backups_on_delete  — true would keep the automated backups after the instance is gone
    //   final_backup_config       — Cloud SQL otherwise takes a final backup on delete and keeps it
    //
    // Both are wired to var.deletion_protection, so an environment that is meant to survive keeps
    // its safety net and a one-off does not leave an orphan nobody remembers to delete.
    retain_backups_on_delete = var.deletion_protection

    final_backup_config {
      enabled = var.deletion_protection
    }

    backup_configuration {
      // On, as the brief requires: a demonstration that eats its own booking table is not a
      // demonstration. The retention is one backup, because nothing here needs a history.
      enabled    = true
      start_time = "02:00"

      // Off by default. Enabling it restarts the instance and archives write-ahead logs as billed
      // storage, and a one-off run has no point in time to recover to.
      point_in_time_recovery_enabled = var.sql_point_in_time_recovery
      transaction_log_retention_days = var.sql_point_in_time_recovery ? 7 : 1

      backup_retention_settings {
        retained_backups = var.deletion_protection ? 7 : 1
        retention_unit   = "COUNT"
      }
    }

    maintenance_window {
      day          = 7 // Sunday
      hour         = 3
      update_track = "stable"
    }

    // Query Insights. The whole point of RoomLock is contention on one table, and this is where a
    // lock wait or a plan regression in expireHolds becomes visible.
    insights_config {
      query_insights_enabled  = true
      record_application_tags = true
      record_client_address   = false // client addresses are ephemeral Cloud Run instance IPs; noise
    }

    database_flags {
      // Three services, each with a Hikari pool of ten (spring.datasource.hikari.maximum-pool-size),
      // plus Flyway at startup, plus a second revision during a rolling deploy, plus a psql session.
      // 60 covers that. It is deliberately not higher: on a shared-core tier every backend costs
      // memory the instance does not have.
      name  = "max_connections"
      value = "60"
    }

    user_labels = var.labels
  }

  depends_on = [
    google_service_networking_connection.main,
    google_project_service.required,
  ]
}

// A database per service, exactly as the local init script creates them.
resource "google_sql_database" "service" {
  for_each = toset(local.databases)

  name     = each.value
  project  = var.project_id
  instance = google_sql_database_instance.main.name

  // There is no OWNER argument on this resource: a database created through the Cloud SQL API is
  // owned by cloudsqlsuperuser, which google_sql_user.app below is a member of. That is what makes
  // Flyway's CREATE TABLE work without a hand-run GRANT, unlike the local stack where
  // 01-create-databases.sh has to name the owner explicitly.
}

// One application user for all three databases, as locally. Splitting it into three would be better
// isolation, but it would also be a divergence from the local stack that the services' single
// SPRING_DATASOURCE_USERNAME cannot express.
//
// A user created through the Cloud SQL API is a member of cloudsqlsuperuser, and that is load
// bearing here rather than incidental: booking-service's db/constraint/V4 runs
// `CREATE EXTENSION IF NOT EXISTS btree_gist` (the exclusion constraint booking_no_overlap is a gist
// index), and on Cloud SQL only a member of cloudsqlsuperuser may create an extension. A user
// created with plain `CREATE ROLE` inside psql could not, and that migration would fail on startup.
resource "google_sql_user" "app" {
  name     = local.sql_app_user
  project  = var.project_id
  instance = google_sql_database_instance.main.name
  password = random_password.sql_app.result
}
