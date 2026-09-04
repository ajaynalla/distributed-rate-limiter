# Terraform (minimal AWS deployment)

Provisions the smallest realistic AWS footprint for this service: an
ECS Fargate service running the container image, and an ElastiCache
Redis node it talks to. This is a foundation demonstrating IaC,
repeatable deployment, and environment separation -- not a
production-hardened network design.

## What it creates

- ECS cluster + Fargate service + task definition (reads
  `RATE_LIMITER_*` env vars, same names `RateLimiterProperties` binds
  locally and in `docker-compose.yml`)
- ElastiCache Redis (single node, no replication)
- Security groups: app port open to `var.allowed_app_cidr`, Redis open
  to the app's security group only
- IAM execution role, CloudWatch log group
- Uses the account's **default VPC/subnets** rather than provisioning a
  new network

## Deliberate simplifications (and what production would add)

| Here | Production would add |
|---|---|
| Default VPC, public subnets | Dedicated VPC, private subnets for app + Redis |
| Task gets a public IP directly, `allowed_app_cidr` gates access | An ALB in front (TLS termination, stable DNS, no task-level public IP) |
| Single ElastiCache node | Multi-AZ replication group with automatic failover |
| No autoscaling | Target-tracking autoscaling on CPU/request count |
| Terraform state assumed local/default backend | Remote state (S3 + DynamoDB lock table) per environment |

These are named, not silently skipped, because "minimal" should be a
stated tradeoff, not an accident.

## Environment separation

`environments/dev.tfvars` and `environments/prod.tfvars` differ on
purpose, not just in size: dev defaults to `FAIL_OPEN` (stay up while
iterating), prod to `FAIL_CLOSED` (never silently bypass the limit) --
see the failure-handling tradeoff in `docs/ARCHITECTURE.md`. Both
require `container_image` (the tag CI pushed) and prod requires
`allowed_app_cidr` to be set to something narrower than the default.

## Usage

```bash
cd terraform
terraform init
terraform plan -var-file=environments/dev.tfvars
terraform apply -var-file=environments/dev.tfvars
```

## What was actually validated here

This project was built in a sandboxed environment whose egress policy
blocks `registry.terraform.io`, so `terraform init`/`plan`/`apply`
against AWS could not be run from here. What *was* verified with a real
Terraform 1.9.8 binary:

- `terraform fmt -check -recursive` -- clean
- `terraform validate` -- gets as far as "provider not installed"
  (i.e. the HCL parses and the required-provider block is read
  correctly); it cannot complete schema validation without
  `terraform init` downloading the AWS provider

Run `terraform init` and `terraform plan` yourself against a real AWS
account/credentials before applying -- as you should for any Terraform
change regardless of who wrote it.
