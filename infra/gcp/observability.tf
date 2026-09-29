// Prometheus scraping does not exist on Cloud Run. There is no static_configs list to point at
// three container names the way infra/prometheus.yml does, because a Cloud Run instance has no
// stable address and nothing outside the instance may reach a port that is not the ingress port.
//
// Google Managed Prometheus solves that by inverting it: a collector *sidecar* runs inside every
// instance, scrapes the application container over localhost, and pushes the samples to the
// Monitoring API. So the three actuator endpoints stay private and are still collected.
//
// There is no Terraform resource for this. The provider has no google_monitoring_run_monitoring or
// anything like it — the whole GMP-on-Cloud-Run contract is "run this image with this YAML at
// /etc/rungmp/config.yaml". So the configuration is authored here as a Secret Manager secret and
// mounted as a volume in run.tf. That is the supported mechanism, not a workaround.

locals {
  // Which port each service serves /actuator/prometheus on. These are server.port from each
  // application.yml and nothing else:
  //   booking-service       8080
  //   payment-service       8081  (gRPC is separate, on 9091)
  //   notification-service  8082
  //
  // Note that for payment-service this is NOT the port Cloud Run routes ingress to. The sidecar
  // scrapes over localhost inside the instance, so a port with no ingress is still scrapable — which
  // is what makes it possible for payment-service to expose gRPC through ingress and metrics not at
  // all.
  actuator_ports = {
    booking      = 8080
    payment      = 8081
    notification = 8082
  }

  cloud_run_service_names = {
    booking      = "booking-service"
    payment      = "payment-service"
    notification = "notification-service"
  }

  // The RunMonitoring document the collector reads. Its shape is Google's, and it reuses the
  // ScrapeEndpoint fields of GMP's PodMonitoring CRD — hence `metricRelabeling` (singular) rather
  // than Prometheus' own `metric_relabel_configs`.
  //
  // The relabeling rule is the interesting part. See the label note in README.md: GMP identifies a
  // Cloud Run service through the `namespace` label of the prometheus_target monitored resource, and
  // infra/grafana/dashboards/roomlock.json filters on `service="booking-service"` instead, because
  // that is the label infra/prometheus.yml attaches to its static targets. Rather than let every
  // panel in that dashboard silently return no data, each scraped sample gets a literal `service`
  // label with the same value the local stack uses. sourceLabels is [__name__] only because a replace
  // rule needs a source to match against; the regex matches everything and the replacement is a
  // constant.
  gmp_configs = {
    for key, port in local.actuator_ports : key => yamlencode({
      apiVersion = "monitoring.googleapis.com/v1beta"
      kind       = "RunMonitoring"
      metadata = {
        name = local.cloud_run_service_names[key]
      }
      spec = {
        endpoints = [{
          port     = port
          path     = "/actuator/prometheus"
          interval = var.gmp_scrape_interval
          metricRelabeling = [{
            action       = "replace"
            sourceLabels = ["__name__"]
            regex        = "(.*)"
            targetLabel  = "service"
            replacement  = local.cloud_run_service_names[key]
          }]
        }]
      }
    })
  }
}

resource "google_secret_manager_secret" "gmp_config" {
  for_each = local.actuator_ports

  secret_id = "roomlock-${var.environment}-gmp-${each.key}"
  project   = var.project_id
  labels    = var.labels

  replication {
    auto {}
  }

  // Not a secret in any real sense — it is a scrape config. Secret Manager is simply the only way to
  // get a file into a Cloud Run container without baking it into an image, and the image is built by
  // Jib from the Gradle build, which this directory does not own.
  deletion_protection = false

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "gmp_config" {
  for_each = local.actuator_ports

  secret      = google_secret_manager_secret.gmp_config[each.key].id
  secret_data = local.gmp_configs[each.key]
}
