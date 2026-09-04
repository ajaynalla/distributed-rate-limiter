variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment name (dev, staging, prod, ...). Used to name/tag every resource so environments never collide in the same account."
  type        = string
}

variable "container_image" {
  description = "Full image reference built and pushed by CI, e.g. <account>.dkr.ecr.<region>.amazonaws.com/distributed-rate-limiter:<tag>. Terraform provisions infrastructure; it does not build or push the image."
  type        = string
}

variable "container_port" {
  description = "Port the app listens on inside the container (server.port)"
  type        = number
  default     = 8080
}

variable "desired_count" {
  description = "Number of Fargate tasks to run"
  type        = number
  default     = 1
}

variable "task_cpu" {
  description = "Fargate task CPU units (256 = 0.25 vCPU)"
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Fargate task memory in MiB"
  type        = number
  default     = 512
}

variable "rate_limiter_limit" {
  description = "Requests allowed per window (rate-limiter.limit)"
  type        = number
  default     = 10
}

variable "rate_limiter_window_millis" {
  description = "Window size in milliseconds (rate-limiter.window-millis)"
  type        = number
  default     = 1000
}

variable "rate_limiter_failure_policy" {
  description = "What the app does when Redis is unreachable: FAIL_OPEN (availability first) or FAIL_CLOSED (protection first). See docs/ARCHITECTURE.md for the tradeoff."
  type        = string
  default     = "FAIL_OPEN"

  validation {
    condition     = contains(["FAIL_OPEN", "FAIL_CLOSED"], var.rate_limiter_failure_policy)
    error_message = "rate_limiter_failure_policy must be FAIL_OPEN or FAIL_CLOSED."
  }
}

variable "redis_node_type" {
  description = "ElastiCache node type"
  type        = string
  default     = "cache.t3.micro"
}

variable "allowed_app_cidr" {
  description = "CIDR allowed to reach the app's public port directly. Defaults wide open (0.0.0.0/0) because this minimal setup exposes the Fargate task's public IP directly instead of fronting it with a load balancer -- a deliberate simplification for a demo/portfolio deployment (see terraform/README.md). Restrict this per environment; a real production deployment would put an ALB in front and drop the task's public IP entirely."
  type        = string
  default     = "0.0.0.0/0"
}
