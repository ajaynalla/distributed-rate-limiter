environment = "dev"
aws_region  = "us-east-1"

# Replace with the image CI pushes, e.g. after `docker build`/push to ECR.
container_image = "REPLACE_WITH_ECR_IMAGE_URI:dev"

desired_count = 1
task_cpu      = 256
task_memory   = 512

rate_limiter_limit         = 10
rate_limiter_window_millis = 1000
# Dev prioritizes staying up over strict enforcement while iterating.
rate_limiter_failure_policy = "FAIL_OPEN"

redis_node_type = "cache.t3.micro"
