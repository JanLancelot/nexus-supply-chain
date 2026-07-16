output "resource_group_name" {
  value       = azurerm_resource_group.rg.name
  description = "The name of the resource group."
}

output "acr_login_server" {
  value       = azurerm_container_registry.acr.login_server
  description = "The login server for the Azure Container Registry."
}

output "backend_api_url" {
  value       = var.enable_compute ? "https://${azurerm_linux_web_app.backend_api[0].default_hostname}" : "N/A"
  description = "The URL of the production Backend API."
}

output "backend_staging_api_url" {
  value       = var.enable_compute ? "https://${azurerm_linux_web_app_slot.backend_api_staging[0].default_hostname}" : "N/A"
  description = "The URL of the staging Backend API."
}

output "frontend_url" {
  value       = var.enable_compute ? "https://${azurerm_linux_web_app.frontend_ui[0].default_hostname}" : "N/A"
  description = "The URL of the production Frontend UI."
}

output "frontend_staging_url" {
  value       = var.enable_compute ? "https://${azurerm_linux_web_app_slot.frontend_ui_staging[0].default_hostname}" : "N/A"
  description = "The URL of the staging Frontend UI."
}

output "postgres_host" {
  value       = var.enable_compute ? azurerm_postgresql_flexible_server.postgres[0].fqdn : "N/A"
  description = "The Fully Qualified Domain Name of the PostgreSQL server."
}

output "redis_host" {
  value       = var.enable_compute ? azurerm_managed_redis.redis[0].hostname : "N/A"
  description = "The hostname of the Redis cache."
}
