// Provider is pinned exactly: every resource and argument in this directory was written against
// hashicorp/google 8.4.0 and checked against that version's own schema (`tofu providers schema`),
// not against memory. Bumping it is a deliberate change that needs a re-read of the Managed Kafka
// and Cloud Run v2 schemas, which is where the arguments here are least stable.
terraform {
  required_version = ">= 1.9.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "8.4.0"
    }

    // Generates the database password, so no human has to invent one and no secret has to be
    // passed in through a variable or a tfvars file.
    random = {
      source  = "hashicorp/random"
      version = "3.7.2"
    }
  }

  // Remote state lives in a bucket that Terraform cannot create for itself (chicken and egg);
  // see the bootstrap section of README.md. Backend blocks take no variables, so the bucket name
  // is supplied at init time:
  //   tofu init -backend-config="bucket=roomlock-tfstate-<project>"
  backend "gcs" {
    prefix = "roomlock/infra"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
