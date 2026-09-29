// One Docker repository for the three service images.
//
// build-logic/src/main/kotlin/roomlock.spring-boot-service.gradle.kts builds them with Jib as
// roomlock/<service>:dev into the local daemon. Deploying means retagging that to
// <region>-docker.pkg.dev/<project>/roomlock-<env>/<service>:<tag>, which is what
// local.image_repository below spells out and what outputs.tf hands to the deploy workflow.

resource "google_artifact_registry_repository" "services" {
  repository_id = "roomlock-${var.environment}"
  project       = var.project_id
  location      = var.region
  format        = "DOCKER"
  description   = "RoomLock service images: booking-service, payment-service, notification-service."
  labels        = var.labels

  docker_config {
    // Tags stay mutable. The deploy workflow pushes a commit SHA, but `dev` is retagged by hand
    // during a local push and immutable tags would refuse that.
    immutable_tags = false
  }

  // Deleting the repository deletes the images in it, so a destroy leaves no orphan. The images are
  // also the one artefact worth keeping after a teardown — see "What survives a destroy" in
  // README.md — so delete them by deleting this repository, deliberately, rather than by accident.
  cleanup_policies {
    id     = "keep-recent-only"
    action = "KEEP"

    most_recent_versions {
      keep_count = 5
    }
  }

  cleanup_policies {
    id     = "delete-stale"
    action = "DELETE"

    condition {
      // Anything older than 30 days that the keep rule above did not protect. Artifact Registry
      // storage is billed per GB and a JRE-based image is not small.
      older_than = "2592000s"
      tag_state  = "ANY"
    }
  }

  depends_on = [google_project_service.required]
}

locals {
  // <region>-docker.pkg.dev/<project>/<repo> — the prefix every image reference is built from.
  image_repository = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.services.repository_id}"
}
