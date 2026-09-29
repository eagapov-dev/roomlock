// One VPC, one subnet. Everything that talks to anything else in this deployment does so over
// private addresses in that subnet:
//
//   Cloud Run revisions  -> Direct VPC egress, one address per instance, from the subnet
//   Kafka brokers        -> broker and bootstrap addresses allocated in the subnet by the service
//   Cloud SQL            -> a private address from the Private Service Access range, reached over
//                           the VPC peering that google_service_networking_connection establishes
//
// There is deliberately no Cloud NAT. Nothing in RoomLock calls out to the internet: the three
// services speak only to Postgres, Kafka, each other and Google APIs. Google APIs are reached over
// Cloud Run's own egress path rather than through the VPC, because vpc_access.egress is
// PRIVATE_RANGES_ONLY (run.tf) — only RFC 1918 destinations are routed into the VPC, so the
// Secret Manager and Monitoring calls never need a NAT gateway or an external address.

locals {
  network_name = "roomlock-${var.environment}"
}

resource "google_compute_network" "main" {
  name    = local.network_name
  project = var.project_id

  // Custom mode: the one subnet below is the only one, in the one region this deployment uses.
  // Auto mode would create a subnet in every region and hand out ranges nobody asked for.
  auto_create_subnetworks = false

  depends_on = [google_project_service.required]
}

resource "google_compute_subnetwork" "main" {
  name    = "${local.network_name}-${var.region}"
  project = var.project_id
  region  = var.region
  network = google_compute_network.main.id

  ip_cidr_range = var.subnet_cidr

  // Lets a resource in this subnet reach Google APIs on their private virtual addresses without an
  // external IP. Cheap, and it is what keeps the deployment free of a NAT gateway.
  private_ip_google_access = true
}

// ---------------------------------------------------------------------------
// Private Service Access — how Cloud SQL gets a private IP and no public one
// ---------------------------------------------------------------------------

// A Cloud SQL instance with a private IP does not live in this VPC. It lives in a Google-managed
// tenant project that is peered with this VPC, and its address comes out of a range reserved here.
resource "google_compute_global_address" "private_service_access" {
  name    = "${local.network_name}-psa"
  project = var.project_id

  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = var.private_service_access_prefix_length
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "main" {
  network = google_compute_network.main.id
  service = "servicenetworking.googleapis.com"

  reserved_peering_ranges = [google_compute_global_address.private_service_access.name]

  // Without this the first apply races the peering: Cloud SQL asks for an address out of a range
  // whose connection is still being created.
  depends_on = [google_project_service.required]
}

// ---------------------------------------------------------------------------
// Firewall
// ---------------------------------------------------------------------------

// A custom-mode VPC has no firewall rules at all, and the implied rule denies every ingress. The
// Kafka brokers hold addresses *in this subnet*, so reaching them is ordinary VPC ingress and needs
// an explicit allow. Cloud SQL does not: its address is on the far side of the peering, so only the
// implied allow-all-egress applies there.
//
// Source and destination are both the subnet range, which is the tightest this can be written:
// the Cloud Run instances draw their Direct VPC egress addresses from the same subnet the brokers
// sit in, and neither side has a stable address to name.
resource "google_compute_firewall" "kafka_from_subnet" {
  name        = "${local.network_name}-allow-kafka"
  project     = var.project_id
  network     = google_compute_network.main.id
  description = "Cloud Run revisions (Direct VPC egress) to the Managed Kafka brokers in this subnet."

  direction = "INGRESS"
  priority  = 1000

  source_ranges      = [var.subnet_cidr]
  destination_ranges = [var.subnet_cidr]

  allow {
    protocol = "tcp"
    // 9092 is the Managed Kafka broker/bootstrap port.
    ports = ["9092"]
  }
}

// Everything else stays denied by the implied ingress rule. No rule is written for the HTTP or gRPC
// ports of the services: Cloud Run ingress is not VPC ingress — it is governed by the `ingress`
// field and the run.invoker IAM binding of each service (run.tf, iam.tf), not by a firewall.
