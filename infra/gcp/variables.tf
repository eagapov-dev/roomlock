// No variable here carries a secret as its default. The database password is generated
// (secrets.tf) rather than supplied, so there is nothing to put in a tfvars file by hand.

variable "project_id" {
  description = "Existing GCP project to deploy into. Terraform does not create the project; see the bootstrap section of README.md."
  type        = string
}

variable "region" {
  description = "Region for every regional resource. Cloud SQL, Managed Kafka and Cloud Run all live here, and the Kafka subnet must be in the same region as the cluster."
  type        = string
  default     = "europe-west1"
}

variable "environment" {
  description = "Short environment name, suffixed onto every resource name so two environments can share a project."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,12}$", var.environment))
    error_message = "environment must be lowercase alphanumeric with hyphens, starting with a letter, at most 13 characters (resource names are built from it)."
  }
}

variable "labels" {
  description = "Labels applied to every resource that supports them."
  type        = map(string)
  default     = { system = "roomlock", managed-by = "opentofu" }
}

// ---------------------------------------------------------------------------
// Network
// ---------------------------------------------------------------------------

variable "subnet_cidr" {
  description = "Primary range of the one subnet. Holds the Direct VPC egress addresses of the Cloud Run revisions and the broker addresses of the Kafka cluster, so it must be roomy: /24 is 256 addresses and Direct VPC egress consumes one per instance."
  type        = string
  default     = "10.20.0.0/24"
}

variable "private_service_access_prefix_length" {
  description = "Size of the range reserved for Private Service Access. Cloud SQL's private IP is allocated from it; /24 is the documented minimum for a single instance and /16 is what Google's own guide reserves when more services follow."
  type        = number
  default     = 20
}

// ---------------------------------------------------------------------------
// Cloud SQL
// ---------------------------------------------------------------------------

variable "sql_tier" {
  description = <<-EOT
    Cloud SQL machine type. Floors, so the choice is visible:
      db-f1-micro       0.6 GiB  — the absolute floor. Too small here: three services open a
                                   ten-connection Hikari pool each, and ~35 live backends do not fit.
      db-g1-small       1.7 GiB  — the default. Shared-core, explicitly excluded from the Cloud SQL
                                   SLA and documented as test/dev only, which is exactly what a
                                   one-off verification run is.
      db-custom-1-3840  3.75 GiB — the smallest custom tier, and the smallest one with SLA coverage.
                                   Use it for anything that is meant to stay up.
  EOT
  type        = string
  default     = "db-g1-small"
}

variable "sql_disk_size_gb" {
  description = "Initial data disk, and 10 GB is the Cloud SQL minimum. Autoresize is on, so this is a floor rather than a cap."
  type        = number
  default     = 10
}

variable "sql_point_in_time_recovery" {
  description = "Whether to archive write-ahead logs for point-in-time recovery. Off by default: a throwaway instance has nothing to recover to, enabling it restarts the instance, and the archived logs are billed storage. Turn it on for an environment that holds bookings anyone cares about."
  type        = bool
  default     = false
}

variable "sql_availability_type" {
  description = "ZONAL (single zone, cheapest) or REGIONAL (a synchronous standby, so roughly double the instance cost). ZONAL by default. REGIONAL also requires a non-shared-core tier, so it cannot be combined with the db-g1-small default of sql_tier."
  type        = string
  default     = "ZONAL"

  validation {
    condition     = contains(["ZONAL", "REGIONAL"], var.sql_availability_type)
    error_message = "sql_availability_type must be ZONAL or REGIONAL."
  }
}

variable "deletion_protection" {
  description = "Guards Cloud SQL, Managed Kafka, the Cloud Run services and the password secret against destroy. FALSE by default, because this deployment is a one-off that is meant to be torn down the moment it has been verified and `tofu destroy` must succeed in one pass. Set it to true for an environment that is meant to survive, and then expect destroy to fail until it is set back."
  type        = bool
  default     = false
}

// ---------------------------------------------------------------------------
// Managed Service for Apache Kafka
// ---------------------------------------------------------------------------

variable "kafka_vcpu_count" {
  description = "vCPUs for the Kafka cluster. 3 is the floor the API enforces, and the default: there is no smaller Managed Kafka cluster to ask for."
  type        = number
  default     = 3

  validation {
    condition     = var.kafka_vcpu_count >= 3
    error_message = "Managed Service for Apache Kafka requires at least 3 vCPUs."
  }
}

variable "kafka_memory_gib" {
  description = "Memory for the Kafka cluster in GiB. The API requires between 1 and 8 GiB per vCPU, so 3 GiB against 3 vCPUs is the floor and the default."
  type        = number
  default     = 3

  validation {
    condition     = var.kafka_memory_gib >= var.kafka_vcpu_count && var.kafka_memory_gib <= var.kafka_vcpu_count * 8
    error_message = "kafka_memory_gib must be between 1 and 8 GiB per vCPU."
  }
}

variable "kafka_broker_disk_size_gib" {
  description = "Disk per broker. 100 GiB is the floor the API enforces, and it is billed per broker, so this is the single largest thing about the cluster that cannot be made smaller."
  type        = number
  default     = 100

  validation {
    condition     = var.kafka_broker_disk_size_gib >= 100
    error_message = "Managed Service for Apache Kafka requires at least 100 GiB of disk per broker."
  }
}

variable "kafka_partitions" {
  description = "Partitions per contract topic. Three, to match KAFKA_NUM_PARTITIONS of infra/docker-compose.yml, so a consumer group behaves the same locally and deployed."
  type        = number
  default     = 3
}

variable "kafka_dlt_partitions" {
  description = "Partitions of the dead-letter topics. One is enough: DeadLetterPublishingRecoverer leaves the partition to the producer precisely because a DLT may be narrower than its source topic."
  type        = number
  default     = 1
}

variable "kafka_replication_factor" {
  description = "Replicas per partition. Three is what the outbox needs: KafkaEventSender sends with acks=all and only marks a row published once every replica has the record, so a single replica would make that guarantee meaningless."
  type        = number
  default     = 3
}

// ---------------------------------------------------------------------------
// Cloud Run
// ---------------------------------------------------------------------------

variable "image_tag" {
  description = "Tag of the three images in Artifact Registry. The deploy workflow overrides it with a commit SHA; `dev` matches what Jib pushes locally."
  type        = string
  default     = "dev"
}

variable "service_cpu" {
  description = "CPU limit per Cloud Run instance. Cloud Run accepts only 1, 2, 4, 6 or 8; 4 or more raises the memory floor."
  type        = string
  default     = "1"

  validation {
    condition     = contains(["1", "2", "4", "6", "8"], var.service_cpu)
    error_message = "Cloud Run accepts only 1, 2, 4, 6 or 8 CPUs."
  }
}

variable "service_memory" {
  description = "Memory limit per Cloud Run instance. Cloud Run's floor at 1 CPU is 512Mi; the default here is 1Gi because the images run with -XX:MaxRAMPercentage=75, so 512Mi would give a Spring Boot service running Hibernate, Flyway and a Kafka client a 384 MB heap. That is not a bet worth making on the one run this deployment exists for."
  type        = string
  default     = "1Gi"
}

variable "max_instance_count" {
  description = "Upper bound on instances per service. Kept at 1 by default because every instance is billed for CPU it never yields (cpu_idle = false), not because a second one would be unsafe: the outbox publisher holds a lease and the sweeps claim their batches with FOR UPDATE SKIP LOCKED."
  type        = number
  default     = 1
}

variable "booking_service_public" {
  description = "Whether booking-service grants run.invoker to allUsers. Default false: the service has no authentication of its own, so an open bookable API is not something to inherit by accident. With false, callers must present a Google ID token and be listed in booking_invoker_members."
  type        = bool
  default     = false
}

variable "booking_invoker_members" {
  description = "IAM members allowed to invoke booking-service when booking_service_public is false, e.g. [\"user:someone@example.com\", \"serviceAccount:loadtest@project.iam.gserviceaccount.com\"]. Empty means nobody but a project owner can reach it."
  type        = list(string)
  default     = []
}

// ---------------------------------------------------------------------------
// Observability
// ---------------------------------------------------------------------------

variable "gmp_sidecar_image" {
  description = "Google's Managed Prometheus collector for Cloud Run. Pinned: it is the component that decides which labels the metrics arrive with, so it is not something to float on a mutable tag."
  type        = string
  default     = "us-docker.pkg.dev/cloud-ops-agents-artifacts/cloud-run-gmp-sidecar/cloud-run-gmp-sidecar:1.2.0"
}

variable "gmp_scrape_interval" {
  description = "How often the sidecar scrapes /actuator/prometheus. The local Prometheus uses 5s; 30s here because roomlock.outbox.unpublished runs a COUNT on outbox_event on every scrape (docs/reliability.md, known gaps) and Managed Prometheus bills per sample ingested."
  type        = string
  default     = "30s"
}

// ---------------------------------------------------------------------------
// CI/CD — Workload Identity Federation for GitHub Actions
// ---------------------------------------------------------------------------

variable "github_repository" {
  description = "The one repository whose Actions workflows may impersonate the deploy service account, as owner/name. The provider's attribute condition pins it, so no other repository's token is accepted."
  type        = string
  default     = "eagapov-dev/roomlock"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name."
  }
}

// The remote state bucket is deliberately NOT a variable and not a resource in this configuration.
// A bucket that holds its own state cannot be destroyed by the run that is reading from it, and the
// point of this deployment is that `tofu destroy` finishes in one pass. It is created by hand in the
// bootstrap (README.md) and outlives every apply and destroy, along with the project and the enabled
// APIs. The backend takes its name at init time, not from a variable.
