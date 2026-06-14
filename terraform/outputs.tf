output "resource_group_name" {
  value       = azurerm_resource_group.rg.name
  description = "The name of the resource group."
}

output "acr_login_server" {
  value       = azurerm_container_registry.acr.login_server
  description = "The login server for the Azure Container Registry."
}

output "backend_api_url" {
  value       = "https://${azurerm_linux_web_app.backend_api.default_hostname}"
  description = "The URL of the production Backend API."
}

output "backend_staging_api_url" {
  value       = "https://${azurerm_linux_web_app_slot.backend_api_staging.default_hostname}"
  description = "The URL of the staging Backend API."
}

output "frontend_url" {
  value       = "https://${azurerm_linux_web_app.frontend_ui.default_hostname}"
  description = "The URL of the production Frontend UI."
}

output "frontend_staging_url" {
  value       = "https://${azurerm_linux_web_app_slot.frontend_ui_staging.default_hostname}"
  description = "The URL of the staging Frontend UI."
}

output "postgres_host" {
  value       = azurerm_postgresql_flexible_server.postgres.fqdn
  description = "The Fully Qualified Domain Name of the PostgreSQL server."
}

output "redis_host" {
  value       = azurerm_managed_redis.redis.hostname
  description = "The hostname of the Redis cache."
}
