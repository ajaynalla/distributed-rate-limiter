provider "aws" {
  region = var.aws_region
}

# Deliberately using the account's default VPC/subnets instead of
# provisioning a new VPC (with NAT gateways, route tables, etc.) -- this
# is a minimal foundation meant to demonstrate IaC and repeatable
# deployment, not a full network topology. A real production rollout
# would use a dedicated VPC with private subnets for the app and
# database tier.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

locals {
  name = "distributed-rate-limiter-${var.environment}"
}

# ---- Security groups ----

resource "aws_security_group" "app" {
  name        = "${local.name}-app"
  description = "Allow inbound app traffic"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "App port"
    from_port   = var.container_port
    to_port     = var.container_port
    protocol    = "tcp"
    cidr_blocks = [var.allowed_app_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${local.name}-app"
    Environment = var.environment
  }
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Allow Redis traffic from the app's security group only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Redis from app"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${local.name}-redis"
    Environment = var.environment
  }
}

# ---- ElastiCache (Redis) ----
# Single node, no cluster mode / replication -- correct minimal choice
# for demonstrating the deployment shape; see terraform/README.md for
# what production would add (multi-AZ, automatic failover).

resource "aws_elasticache_subnet_group" "this" {
  name       = "${local.name}-redis-subnets"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id         = "${local.name}-redis"
  engine             = "redis"
  engine_version     = "7.1"
  node_type          = var.redis_node_type
  num_cache_nodes    = 1
  port               = 6379
  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  tags = {
    Name        = "${local.name}-redis"
    Environment = var.environment
  }
}

# ---- ECS (Fargate) ----

resource "aws_ecs_cluster" "this" {
  name = local.name

  tags = {
    Environment = var.environment
  }
}

resource "aws_iam_role" "task_execution" {
  name = "${local.name}-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })

  tags = {
    Environment = var.environment
  }
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name}"
  retention_in_days = 14

  tags = {
    Environment = var.environment
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.task_execution.arn

  container_definitions = jsonencode([
    {
      name      = "app"
      image     = var.container_image
      essential = true
      portMappings = [{
        containerPort = var.container_port
        protocol      = "tcp"
      }]
      # Same RATE_LIMITER_* environment variables the app binds locally
      # and in docker-compose.yml (see RateLimiterProperties) -- no
      # separate "cloud config" format to keep in sync.
      environment = [
        { name = "RATE_LIMITER_MODE", value = "REDIS" },
        { name = "RATE_LIMITER_LIMIT", value = tostring(var.rate_limiter_limit) },
        { name = "RATE_LIMITER_WINDOW_MILLIS", value = tostring(var.rate_limiter_window_millis) },
        { name = "RATE_LIMITER_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "RATE_LIMITER_REDIS_PORT", value = tostring(aws_elasticache_cluster.redis.cache_nodes[0].port) },
        { name = "RATE_LIMITER_REDIS_FAILURE_POLICY", value = var.rate_limiter_failure_policy },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "app"
        }
      }
    }
  ])

  tags = {
    Environment = var.environment
  }
}

resource "aws_ecs_service" "app" {
  name            = local.name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets = data.aws_subnets.default.ids
    security_groups = [
      aws_security_group.app.id
    ]
    # No ALB in front, so the task needs a public IP to be reachable at
    # all -- see var.allowed_app_cidr and terraform/README.md.
    assign_public_ip = true
  }

  tags = {
    Environment = var.environment
  }
}
