output "resource_group_name" {
  value       = azurerm_resource_group.rg.name
  description = "The name of the resource group."
}

output "acr_login_server" {
  value       = azurerm_container_registry.acr.login_server
  description = "The login server for the Azure Container Registry."
}

output "app_url" {
  value       = var.enable_compute ? "https://${azurerm_linux_web_app.backend_api[0].default_hostname}" : "N/A"
  description = "The URL of the consolidated production Application."
}

output "app_staging_url" {
  value       = var.enable_compute ? "https://${azurerm_linux_web_app_slot.backend_api_staging[0].default_hostname}" : "N/A"
  description = "The URL of the consolidated staging Application."
}

output "postgres_host" {
  value       = azurerm_postgresql_flexible_server.postgres.fqdn
  description = "The Fully Qualified Domain Name of the PostgreSQL server."
}

output "redis_host" {
  value       = var.enable_compute ? azurerm_managed_redis.redis[0].hostname : "N/A"
  description = "The hostname of the Redis cache."
}
