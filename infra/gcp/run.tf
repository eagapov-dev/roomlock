// The three services. This is where RoomLock stops being a default Cloud Run deployment, for two
// reasons that are worth reading before changing anything here: CPU is always allocated, and only one
// of the three is reachable from outside.

locals {
  // Environment shared by all three. Every name here is a property that already exists in the
  // service's application.yml, overridden through Spring's relaxed binding — the same mechanism
  // infra/docker-compose.yml uses. Nothing invents a new property.
  common_env = {
    // infra/docker-compose.yml sets SPRING_DATASOURCE_USERNAME from ROOMLOCK_DB_USER; same here.
    SPRING_DATASOURCE_USERNAME = google_sql_user.app.name

    // The bootstrap address of the Managed Kafka cluster, replacing the localhost:9094 of a local run
    // and the kafka:9092 of the compose network.
    SPRING_KAFKA_BOOTSTRAP_SERVERS = google_managed_kafka_cluster.main.bootstrap_address
  }

  // Cloud SQL is reached at its private address over Direct VPC egress: plain TCP, no proxy, no
  // sidecar, no unix socket. That is why there is no Cloud SQL volume mount on any of these services
  // and why the JDBC URL needs no socketFactory — which matters, because a socketFactory would need a
  // dependency added to the Gradle build, and the build is not this directory's to change.
  jdbc_urls = {
    for key, db in {
      booking      = "booking"
      payment      = "payment"
      notification = "notification"
    } : key => "jdbc:postgresql://${google_sql_database_instance.main.private_ip_address}:5432/${db}"
  }
}

// ---------------------------------------------------------------------------
// booking-service — the only service a client talks to
// ---------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "booking" {
  name     = "booking-service"
  project  = var.project_id
  location = var.region
  labels   = var.labels

  // INGRESS_TRAFFIC_ALL is a network statement, not an authorisation one. Who may actually call it is
  // decided by the run.invoker bindings in iam.tf, which default to a named list rather than allUsers.
  ingress = "INGRESS_TRAFFIC_ALL"

  // Left at the default (false) so the IAM invoker check stays on. Setting it true would make the
  // service answer anyone who can route to it, regardless of the bindings in iam.tf.
  invoker_iam_disabled = false

  deletion_protection = var.deletion_protection

  template {
    service_account = google_service_account.booking.email
    labels          = var.labels

    scaling {
      // min_instance_count = 1, and this is the load-bearing line of the whole deployment.
      //
      // booking-service runs two @Scheduled sweeps: OutboxScheduler every roomlock.outbox.interval
      // (PT1S) publishes the transactional outbox, and HoldExpiryScheduler every
      // roomlock.hold.expiry.interval (PT30S) cancels expired holds. Both are what make the
      // reliability argument in docs/reliability.md true — an event is published because a sweep
      // publishes it, and a room is released because a sweep releases it.
      //
      // Scale to zero would stop both. With no instance running, a committed outbox row is never sent
      // and an abandoned hold blocks its room past roomlock.hold.ttl forever, until the next request
      // happens to start an instance. That is not a degraded deployment, it is a broken one.
      min_instance_count = 1

      // Capped at 1 by default: the outbox publisher has no leader election and its pending query
      // takes no lock, so two replicas both sweep and both publish every event (docs/reliability.md,
      // known gaps). The consumers deduplicate on eventId so the system stays correct, but the
      // duplication is an accident of the consumers rather than a design, and it is not something to
      // turn on silently by scaling out.
      max_instance_count = var.max_instance_count
    }

    vpc_access {
      // Direct VPC egress: the revision gets an address in the subnet itself. No Serverless VPC
      // Access connector, which would be a separate always-billed set of instances.
      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.main.id
      }

      // Only RFC 1918 destinations go through the VPC — Postgres and Kafka. Google APIs (Secret
      // Manager, Monitoring) keep using Cloud Run's own egress path, which is why this deployment
      // needs no Cloud NAT and no external address anywhere.
      egress = "PRIVATE_RANGES_ONLY"
    }

    containers {
      name  = "booking-service"
      image = "${local.image_repository}/booking-service:${var.image_tag}"

      ports {
        container_port = 8080 // server.port of booking-service/src/main/resources/application.yml
        name           = "http1"
      }

      resources {
        limits = {
          cpu    = var.service_cpu
          memory = var.service_memory
        }

        // cpu_idle = false — "CPU always allocated".
        //
        // The Cloud Run default throttles CPU to near zero outside a request. A @Scheduled task is
        // not a request, so under the default the outbox sweep and the hold expiry sweep would only
        // run while a client happened to be calling the service, at whatever rate the throttle
        // allowed. The two sweeps are the reason this service is correct; they have to run on their
        // own schedule, so the CPU has to be there when no request is.
        cpu_idle = false

        // The JVM, Flyway and Hibernate schema validation all happen before the first request. Boost
        // shortens that without changing the steady-state bill.
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = merge(local.common_env, {
          SPRING_DATASOURCE_URL = local.jdbc_urls.booking

          // gRPC to payment-service.
          //
          // Cloud Run terminates TLS at its frontend on 443 and forwards to the container as HTTP/2
          // cleartext — that is what `name = "h2c"` on payment-service's port below arranges, and it
          // is what makes HTTP/2 end to end and therefore gRPC work at all. The client side must
          // therefore speak TLS to :443, not the plaintext of a local run, which is why
          // negotiation-type is overridden here as well as the address.
          //
          // AUTHENTICATION: payment-service requires run.invoker (iam.tf) and is internal-only, so
          // every call needs an Authorization: Bearer <ID token> header whose token was minted for an
          // *audience equal to payment-service's own URL* — an access token or a token for the wrong
          // audience is rejected. On Cloud Run the token comes from the instance metadata server:
          //   GET http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=<callee URL>
          //   Metadata-Flavor: Google
          // Attaching it to the gRPC channel is a client-side concern (a CallCredentials on the
          // channel built by PaymentChannelConfig) and belongs to the application, not here. The
          // audience it must use is published as the payment_service_url output.
          SPRING_GRPC_CLIENT_CHANNELS_PAYMENT_ADDRESS          = "static://${replace(google_cloud_run_v2_service.payment.uri, "https://", "")}:443"
          SPRING_GRPC_CLIENT_CHANNELS_PAYMENT_NEGOTIATION_TYPE = "tls"
        })

        content {
          name  = env.key
          value = env.value
        }
      }

      // Resolved from Secret Manager at instance start, so the password never appears in the
      // service's configuration, in `gcloud run services describe`, or in a deploy log.
      env {
        name = "SPRING_DATASOURCE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.sql_password.secret_id
            version = "latest"
          }
        }
      }

      startup_probe {
        // Flyway runs four migration locations against a cold Cloud SQL instance before the actuator
        // answers, so the window is generous.
        initial_delay_seconds = 15
        period_seconds        = 10
        timeout_seconds       = 5
        failure_threshold     = 30

        http_get {
          path = "/actuator/health"
          port = 8080
        }
      }

      volume_mounts {
        name       = "gmp-config"
        mount_path = "/etc/rungmp"
      }
    }

    // The Managed Prometheus collector. Same identity as the service, scrapes localhost:8080.
    containers {
      name  = "collector"
      image = var.gmp_sidecar_image

      // Start after the application container and shut down before it, so the collector is never
      // scraping a port nothing is listening on. This is the typed form of the
      // run.googleapis.com/container-dependencies annotation Google's sidecar guide shows; setting
      // both would be the same field twice.
      depends_on = ["booking-service"]

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        // The collector scrapes on a timer, not in response to a request, for exactly the same reason
        // the application container does not: a throttled sidecar stops scraping between requests.
        cpu_idle = false
      }

      volume_mounts {
        name       = "gmp-config"
        mount_path = "/etc/rungmp"
      }
    }

    volumes {
      name = "gmp-config"
      secret {
        secret = google_secret_manager_secret.gmp_config["booking"].secret_id
        items {
          // Lands at /etc/rungmp/config.yaml, which is where the collector looks.
          path    = "config.yaml"
          version = "latest"
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_version.sql_password,
    google_secret_manager_secret_iam_member.sql_password,
    google_secret_manager_secret_iam_member.gmp_config,
  ]
}

// ---------------------------------------------------------------------------
// payment-service — reached only by booking-service, over gRPC
// ---------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "payment" {
  name     = "payment-service"
  project  = var.project_id
  location = var.region
  labels   = var.labels

  // Internal only. payment-service serves Charge and Refund; the only caller in the whole system is
  // booking-service's GrpcPaymentGateway, and the service authenticates nothing itself — a declined
  // charge is a business outcome, not an authorisation decision. So the internet is kept out at the
  // network layer here, and everything else inside the project is kept out at the IAM layer by the
  // single run.invoker binding in iam.tf. Both halves are needed: internal-only alone would still
  // admit any other workload in the VPC.
  ingress = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  invoker_iam_disabled = false

  deletion_protection = var.deletion_protection

  template {
    service_account = google_service_account.payment.email
    labels          = var.labels

    scaling {
      // min_instance_count = 1, for the consumer rather than a scheduler: RefundRequestsListener is a
      // Spring Kafka listener container, and a listener only receives a record while its consumer is
      // polling. With no instance running nothing polls roomlock.refund-requests.v1, so a refund sits
      // in the topic unhandled — and nothing will ever start an instance, because the trigger is a
      // Kafka record and not an HTTP request. A request-driven service can afford to scale to zero; a
      // consumer cannot.
      min_instance_count = 1
      max_instance_count = var.max_instance_count
    }

    vpc_access {
      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.main.id
      }
      egress = "PRIVATE_RANGES_ONLY"
    }

    containers {
      name  = "payment-service"
      image = "${local.image_repository}/payment-service:${var.image_tag}"

      // A Cloud Run service routes ingress to exactly one container port, and payment-service listens
      // on two: HTTP 8081 (server.port) and gRPC 9091 (spring.grpc.server.port). The one that has to
      // be reachable is gRPC, because that is the only edge anything crosses to get here — so 9091 is
      // the ingress port and 8081 stays internal to the instance.
      //
      // Nothing is lost by that. /actuator/prometheus on 8081 is scraped by the collector sidecar over
      // localhost, and the startup probe below is pointed at 8081 explicitly.
      //
      // name = "h2c" is what makes gRPC work: it tells Cloud Run to forward to this container as
      // HTTP/2 cleartext after terminating TLS at the frontend, giving HTTP/2 end to end. With the
      // default ("http1") the frontend would downgrade the connection and every gRPC call would fail.
      ports {
        container_port = 9091
        name           = "h2c"
      }

      resources {
        limits = {
          cpu    = var.service_cpu
          memory = var.service_memory
        }

        // See booking-service. Here the reason is the Kafka consumer poll loop and not a @Scheduled
        // sweep, but it is the same failure: a throttled instance stops polling between requests, and
        // this service receives no requests at all except gRPC calls that may be hours apart.
        cpu_idle          = false
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = merge(local.common_env, {
          SPRING_DATASOURCE_URL = local.jdbc_urls.payment
        })
        content {
          name  = env.key
          value = env.value
        }
      }

      env {
        name = "SPRING_DATASOURCE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.sql_password.secret_id
            version = "latest"
          }
        }
      }

      startup_probe {
        initial_delay_seconds = 15
        period_seconds        = 10
        timeout_seconds       = 5
        failure_threshold     = 30

        // Deliberately the HTTP actuator on 8081 rather than the gRPC ingress port. The probe's port
        // defaults to the container port, so it is named explicitly; /actuator/health is a real
        // readiness signal (Flyway finished, the datasource is up) in a way a TCP connect to 9091 is
        // not, and it avoids depending on whether the gRPC health service is registered.
        http_get {
          path = "/actuator/health"
          port = 8081
        }
      }

      volume_mounts {
        name       = "gmp-config"
        mount_path = "/etc/rungmp"
      }
    }

    containers {
      name  = "collector"
      image = var.gmp_sidecar_image

      depends_on = ["payment-service"]

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle = false
      }

      volume_mounts {
        name       = "gmp-config"
        mount_path = "/etc/rungmp"
      }
    }

    volumes {
      name = "gmp-config"
      secret {
        secret = google_secret_manager_secret.gmp_config["payment"].secret_id
        items {
          path    = "config.yaml"
          version = "latest"
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_version.sql_password,
    google_secret_manager_secret_iam_member.sql_password,
    google_secret_manager_secret_iam_member.gmp_config,
  ]
}

// ---------------------------------------------------------------------------
// notification-service — reached by nobody
// ---------------------------------------------------------------------------

resource "google_cloud_run_v2_service" "notification" {
  name     = "notification-service"
  project  = var.project_id
  location = var.region
  labels   = var.labels

  // Internal only, and with no run.invoker binding anywhere in iam.tf. Nothing calls this service:
  // its input is roomlock.booking-events.v1 and its output is a NotificationSender. The HTTP port
  // exists because Spring Boot has one and because the actuator lives on it, and the actuator is
  // scraped from inside the instance. There is no caller to authorise, so nobody is authorised.
  ingress = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  invoker_iam_disabled = false

  deletion_protection = var.deletion_protection

  template {
    service_account = google_service_account.notification.email
    labels          = var.labels

    scaling {
      // min_instance_count = 1, same reason as payment-service and more starkly: this service has no
      // HTTP API at all, so nothing will ever wake it. If it is not running, BookingEventsListener is
      // not polling and no guest is ever notified.
      min_instance_count = 1
      max_instance_count = var.max_instance_count
    }

    vpc_access {
      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.main.id
      }
      egress = "PRIVATE_RANGES_ONLY"
    }

    containers {
      name  = "notification-service"
      image = "${local.image_repository}/notification-service:${var.image_tag}"

      ports {
        container_port = 8082 // server.port of notification-service/src/main/resources/application.yml
        name           = "http1"
      }

      resources {
        limits = {
          cpu    = var.service_cpu
          memory = var.service_memory
        }

        // See above. A consumer with no HTTP traffic at all is the clearest case for this: under the
        // default throttle it would receive nothing, forever.
        cpu_idle          = false
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = merge(local.common_env, {
          SPRING_DATASOURCE_URL = local.jdbc_urls.notification
        })
        content {
          name  = env.key
          value = env.value
        }
      }

      env {
        name = "SPRING_DATASOURCE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.sql_password.secret_id
            version = "latest"
          }
        }
      }

      startup_probe {
        initial_delay_seconds = 15
        period_seconds        = 10
        timeout_seconds       = 5
        failure_threshold     = 30

        http_get {
          path = "/actuator/health"
          port = 8082
        }
      }

      volume_mounts {
        name       = "gmp-config"
        mount_path = "/etc/rungmp"
      }
    }

    containers {
      name  = "collector"
      image = var.gmp_sidecar_image

      depends_on = ["notification-service"]

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle = false
      }

      volume_mounts {
        name       = "gmp-config"
        mount_path = "/etc/rungmp"
      }
    }

    volumes {
      name = "gmp-config"
      secret {
        secret = google_secret_manager_secret.gmp_config["notification"].secret_id
        items {
          path    = "config.yaml"
          version = "latest"
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_version.sql_password,
    google_secret_manager_secret_iam_member.sql_password,
    google_secret_manager_secret_iam_member.gmp_config,
  ]
}
