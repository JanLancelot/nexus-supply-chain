output "resource_group_name" {
  value       = azurerm_resource_group.rg.name
  description = "The name of the resource group."
}

output "acr_login_server" {
  value       = azurerm_container_registry.acr.login_server
  description = "The login server for the Azure Container Registry."
}

output "app_url" {
  value       = "https://${azurerm_linux_web_app.backend_api.default_hostname}"
  description = "The URL of the consolidated production Application."
}

output "app_staging_url" {
  value       = "https://${azurerm_linux_web_app_slot.backend_api_staging.default_hostname}"
  description = "The URL of the consolidated staging Application."
}

output "postgres_host" {
  value       = azurerm_postgresql_flexible_server.postgres.fqdn
  description = "The Fully Qualified Domain Name of the PostgreSQL server."
}

output "redis_host" {
  value       = azurerm_managed_redis.redis.hostname
  description = "The hostname of the Redis cache."
}
