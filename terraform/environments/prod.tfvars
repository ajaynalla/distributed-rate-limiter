environment = "prod"
aws_region  = "us-east-1"

# Replace with the image CI pushes, e.g. after `docker build`/push to ECR.
container_image = "REPLACE_WITH_ECR_IMAGE_URI:prod"

desired_count = 2
task_cpu      = 512
task_memory   = 1024

rate_limiter_limit         = 100
rate_limiter_window_millis = 1000
# Prod prioritizes never silently bypassing the limit over availability;
# revisit this choice alongside what the limiter protects (see
# docs/ARCHITECTURE.md's failure-handling tradeoff discussion).
rate_limiter_failure_policy = "FAIL_CLOSED"

redis_node_type = "cache.t3.small"

# Lock this down to your actual office/VPN/allowed CIDR before applying.
allowed_app_cidr = "REPLACE_WITH_YOUR_CIDR/32"
