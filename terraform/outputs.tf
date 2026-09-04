output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  description = "ECS service name"
  value       = aws_ecs_service.app.name
}

output "redis_endpoint" {
  description = "ElastiCache Redis endpoint the app connects to"
  value       = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "cloudwatch_log_group" {
  description = "Where the app's container logs land"
  value       = aws_cloudwatch_log_group.app.name
}
