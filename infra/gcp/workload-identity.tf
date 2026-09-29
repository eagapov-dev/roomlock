// Workload Identity Federation for GitHub Actions. No service account key exists anywhere in this
// configuration, and none should ever be created: a key is a long-lived credential in a repository
// secret that cannot be revoked without noticing it leaked first. Actions exchanges its own
// short-lived OIDC token for a Google access token instead, and the exchange is restricted to one
// repository.
//
// The deploy workflow itself is .github/workflows/**, which this directory does not own. What it needs
// from here is published by outputs.tf: the provider name and the deploy service account's e-mail.

locals {
  github_owner = split("/", var.github_repository)[0]
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "roomlock-${var.environment}-github"
  project                   = var.project_id
  display_name              = "RoomLock GitHub Actions (${var.environment})"
  description               = "Federates GitHub Actions OIDC tokens from ${var.github_repository}."

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-actions"
  project                            = var.project_id
  display_name                       = "GitHub Actions OIDC"

  attribute_mapping = {
    // google.subject must be unique per workload; GitHub's `sub` already is.
    "google.subject" = "assertion.sub"

    // attribute.repository is what the principalSet binding below keys on.
    "attribute.repository" = "assertion.repository"

    // Carried through so a binding can be narrowed to a branch or an environment later without
    // touching the provider.
    "attribute.repository_owner" = "assertion.repository_owner"
    "attribute.ref"              = "assertion.ref"
  }

  // Pinned to the one repository. Without an attribute_condition, *any* GitHub repository in the world
  // could present a valid token to this provider and the only thing standing between it and the deploy
  // service account would be the principalSet binding below. Two independent checks, because this is
  // the boundary between the public internet and a service account that can deploy.
  attribute_condition = "assertion.repository == '${var.github_repository}' && assertion.repository_owner == '${local.github_owner}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"

    // GitHub mints the token with an audience of the provider's own resource name unless a workflow
    // asks otherwise; google-github-actions/auth sends that by default.
    allowed_audiences = []
  }
}

// ---------------------------------------------------------------------------
// The deploy service account
// ---------------------------------------------------------------------------

resource "google_service_account" "deploy" {
  account_id   = "roomlock-${var.environment}-deploy"
  display_name = "RoomLock CI deploy (${var.environment})"
  description  = "Impersonated by GitHub Actions from ${var.github_repository}. Pushes images and rolls Cloud Run revisions; owns nothing else."
  project      = var.project_id

  depends_on = [google_project_service.required]
}

// Only tokens carrying attribute.repository == var.github_repository may impersonate it. Note this is
// a principalSet, not a principal: it matches every workflow run of that repository, which is the
// finest grain available without also pinning a branch or a GitHub environment.
resource "google_service_account_iam_member" "deploy_from_github" {
  service_account_id = google_service_account.deploy.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

// What a deploy actually needs, and nothing more.
//
//   artifactregistry.writer — push a new image tag. Not .admin: a deploy has no business deleting a
//                            repository or rewriting its cleanup policies.
//   run.developer          — create a revision of an existing service and shift traffic to it. Not
//                            run.admin, which also carries setIamPolicy, i.e. the ability to grant
//                            allUsers the invoker role on booking-service and undo the decision in
//                            iam.tf.
resource "google_project_iam_member" "deploy" {
  for_each = toset([
    "roles/artifactregistry.writer",
    "roles/run.developer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.deploy.email}"
}

// Deploying a revision that runs as a runtime service account requires iam.serviceAccounts.actAs on
// that account. Granted on each of the three accounts individually rather than project-wide, so the
// deploy identity can act as exactly these three and no other service account that ever appears in
// the project.
resource "google_service_account_iam_member" "deploy_act_as_runtime" {
  for_each = local.service_accounts

  service_account_id = each.value.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deploy.email}"
}

// Deliberately not granted to the deploy account:
//   - secretmanager.* — a deploy never reads or writes the database password; the running service
//     resolves it at instance start under its own identity.
//   - cloudsql.* — migrations run as Flyway inside the service at startup, not from CI.
//   - any Terraform-shaped role. This configuration is applied by a human with their own credentials;
//     CI builds and deploys images, and cannot change the infrastructure it deploys onto.
