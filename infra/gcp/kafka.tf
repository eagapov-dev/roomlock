// Google Managed Service for Apache Kafka. This is a real Kafka cluster, not Pub/Sub: the contract
// in common/src/main/java/dev/roomlock/contract/v1 is Kafka records with a key, a header and an
// ordering guarantee per partition, and the consumers are Spring Kafka listener containers with a
// DeadLetterPublishingRecoverer. None of that survives a translation to Pub/Sub.
//
// The cluster is provisioned capacity. It has no idle state and no scale to zero — see the cost
// section of README.md.

locals {
  // The two topics of the contract (dev.roomlock.contract.v1.Topics) and the dead-letter topic each
  // consumer parks an unhandleable record on. The suffix is roomlock.*.dead-letter-suffix in the
  // two application.yml files; both are ".DLT".
  kafka_topics = {
    "roomlock.booking-events.v1" = {
      partitions = var.kafka_partitions
      comment    = "BookingHeld, BookingConfirmed, BookingCancelled; produced by the booking-service outbox, consumed by notification-service"
    }
    "roomlock.refund-requests.v1" = {
      partitions = var.kafka_partitions
      comment    = "RefundRequested only; produced by the booking-service outbox, consumed by payment-service"
    }
    "roomlock.booking-events.v1.DLT" = {
      partitions = var.kafka_dlt_partitions
      comment    = "where notification-service's DefaultErrorHandler parks a record it can never handle"
    }
    "roomlock.refund-requests.v1.DLT" = {
      partitions = var.kafka_dlt_partitions
      comment    = "where payment-service's DefaultErrorHandler parks a record it can never handle"
    }
  }
}

resource "google_managed_kafka_cluster" "main" {
  cluster_id = "roomlock-${var.environment}"
  project    = var.project_id
  location   = var.region

  capacity_config {
    // The service floor is 3 vCPU and 1-8 GiB per vCPU. Both are strings in the API.
    vcpu_count   = tostring(var.kafka_vcpu_count)
    memory_bytes = tostring(var.kafka_memory_gib * 1024 * 1024 * 1024)
  }

  broker_capacity_config {
    disk_size_gib = tostring(var.kafka_broker_disk_size_gib)
  }

  gcp_config {
    access_config {
      // Private only. The brokers and the bootstrap address get addresses in this subnet, which is
      // the same subnet the Cloud Run revisions egress from, so nothing about this cluster is
      // reachable from the internet. public_cluster_config is deliberately not set: declaring it
      // would require an allowed_source_ip_ranges list, and there is no caller outside the VPC.
      network_configs {
        // The API wants the bare relative name, not a self_link with a scheme.
        subnet = "projects/${var.project_id}/regions/${var.region}/subnetworks/${google_compute_subnetwork.main.name}"
      }
    }
  }

  rebalance_config {
    // Spread partitions over the new brokers when the cluster is scaled up. Without it a vCPU
    // increase adds capacity that holds no partitions.
    mode = "AUTO_REBALANCE_ON_SCALE_UP"
  }

  labels = var.labels

  // PREVENT makes the API refuse the delete, which is the Kafka equivalent of Cloud SQL's
  // deletion_protection: the topics hold the only copy of an event between the outbox marking it
  // published and a consumer handling it.
  deletion_policy = var.deletion_protection ? "PREVENT" : "DELETE"

  depends_on = [google_project_service.required]
}

// The provider has no argument for "the dead-letter topic of this topic" — Kafka has no such
// concept, and neither does the Managed Kafka API. A dead-letter topic is an ordinary topic whose
// name happens to be "<source>.DLT", which is exactly how DeadLetterPublishingRecoverer addresses
// it. So all four are declared the same way, and only the partition count differs.
//
// They are declared here rather than left to broker auto-creation on purpose: an auto-created topic
// takes the cluster default partition count, and the contract depends on the count (bookingId is
// the key, so all events of one booking must land on one partition and stay ordered).
resource "google_managed_kafka_topic" "contract" {
  for_each = local.kafka_topics

  topic_id = each.key
  cluster  = google_managed_kafka_cluster.main.cluster_id
  project  = var.project_id
  location = var.region

  partition_count    = each.value.partitions
  replication_factor = var.kafka_replication_factor

  configs = {
    // OutboxPublisher sends with acks=all and only marks a row published once every replica has the
    // record (docs/reliability.md). min.insync.replicas must therefore be more than 1, or "acks=all"
    // would be satisfied by a single replica and the guarantee would be empty.
    "min.insync.replicas" = tostring(max(var.kafka_replication_factor - 1, 1))

    // Both consumers set auto-offset-reset: earliest and deduplicate on the table rather than on the
    // offset, so a redeployed consumer group replays the topic from the start by design. A week of
    // retention keeps that replay bounded.
    "retention.ms" = tostring(7 * 24 * 60 * 60 * 1000)

    // Delete, not compact. These are events, not a keyed snapshot: two BookingCancelled events for
    // one bookingId are different facts, and compaction would drop one of them.
    "cleanup.policy" = "delete"
  }
}

// ---------------------------------------------------------------------------
// Kafka ACLs — authorisation after the IAM check
// ---------------------------------------------------------------------------

// Managed Kafka authorises in two stages. IAM decides whether a principal may *connect*
// (managedkafka.clusters.connect, granted through roles/managedkafka.client in iam.tf); after that,
// ordinary Kafka ACLs decide what it may do. IAM alone cannot express "produce to this topic only",
// and the provider has no cluster-level IAM resource, so the per-topic narrowing has to happen here.
//
// The topology being enforced is the one in docs/architecture.md: booking-service produces both
// topics and consumes neither; notification-service consumes booking events; payment-service
// consumes refund requests. Each consumer also writes to its own dead-letter topic, because the
// DeadLetterPublishingRecoverer producing the parked record lives inside the consumer.

locals {
  // principal is the Kafka StandardAuthorizer form of a Google service account.
  kafka_principals = {
    booking      = "User:${google_service_account.booking.email}"
    payment      = "User:${google_service_account.payment.email}"
    notification = "User:${google_service_account.notification.email}"
  }

  // topic name -> list of { principal, operation }
  kafka_topic_acls = {
    "roomlock.booking-events.v1" = [
      { principal = local.kafka_principals.booking, operation = "WRITE" },
      { principal = local.kafka_principals.booking, operation = "DESCRIBE" },
      { principal = local.kafka_principals.notification, operation = "READ" },
      { principal = local.kafka_principals.notification, operation = "DESCRIBE" },
    ]
    "roomlock.refund-requests.v1" = [
      { principal = local.kafka_principals.booking, operation = "WRITE" },
      { principal = local.kafka_principals.booking, operation = "DESCRIBE" },
      { principal = local.kafka_principals.payment, operation = "READ" },
      { principal = local.kafka_principals.payment, operation = "DESCRIBE" },
    ]
    // Only the consumer that parks a record writes to its dead-letter topic. Nothing reads these
    // today — docs/reliability.md lists that as a known gap, and no ACL invents a reader.
    "roomlock.booking-events.v1.DLT" = [
      { principal = local.kafka_principals.notification, operation = "WRITE" },
      { principal = local.kafka_principals.notification, operation = "DESCRIBE" },
    ]
    "roomlock.refund-requests.v1.DLT" = [
      { principal = local.kafka_principals.payment, operation = "WRITE" },
      { principal = local.kafka_principals.payment, operation = "DESCRIBE" },
    ]
  }
}

resource "google_managed_kafka_acl" "topic" {
  for_each = local.kafka_topic_acls

  // acl_id encodes the resource pattern: "topic/{name}" is a literal single topic.
  acl_id   = "topic/${each.key}"
  cluster  = google_managed_kafka_cluster.main.cluster_id
  project  = var.project_id
  location = var.region

  dynamic "acl_entries" {
    for_each = each.value
    content {
      principal       = acl_entries.value.principal
      permission_type = "ALLOW"
      operation       = acl_entries.value.operation
      // Managed Service for Apache Kafka requires "*"; the brokers see an internal address, not the
      // caller's, so a host restriction could not mean anything here.
      host = "*"
    }
  }

  depends_on = [google_managed_kafka_topic.contract]
}

// A consumer group is a separate ACL resource type from the topic it reads. spring.kafka.consumer
// .group-id is the service name in both application.yml files, so the group name is known and can be
// a literal rather than a prefix wildcard.
resource "google_managed_kafka_acl" "consumer_group" {
  for_each = {
    "notification-service" = local.kafka_principals.notification
    "payment-service"      = local.kafka_principals.payment
  }

  acl_id   = "consumerGroup/${each.key}"
  cluster  = google_managed_kafka_cluster.main.cluster_id
  project  = var.project_id
  location = var.region

  acl_entries {
    principal       = each.value
    permission_type = "ALLOW"
    operation       = "READ"
    host            = "*"
  }

  acl_entries {
    principal       = each.value
    permission_type = "ALLOW"
    operation       = "DESCRIBE"
    host            = "*"
  }

  depends_on = [google_managed_kafka_topic.contract]
}

// booking-service is a producer only and has no consumer group: nothing in it reads a topic.
// It still needs cluster DESCRIBE so the producer can fetch metadata for the topics it writes to.
resource "google_managed_kafka_acl" "cluster_describe" {
  acl_id   = "cluster"
  cluster  = google_managed_kafka_cluster.main.cluster_id
  project  = var.project_id
  location = var.region

  dynamic "acl_entries" {
    for_each = values(local.kafka_principals)
    content {
      principal       = acl_entries.value
      permission_type = "ALLOW"
      operation       = "DESCRIBE"
      host            = "*"
    }
  }
}
