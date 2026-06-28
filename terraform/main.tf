terraform {
  required_version = ">= 1.3.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {}
  subscription_id = var.azure_subscription_id != "" ? var.azure_subscription_id : null
  tenant_id       = var.azure_tenant_id != "" ? var.azure_tenant_id : null
}

# Resource Group
resource "azurerm_resource_group" "rg" {
  name     = "rg-${var.project_name}-${var.environment}"
  location = var.location
}

# Azure Container Registry
resource "azurerm_container_registry" "acr" {
  name                = var.acr_name
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku                 = "Basic"
  admin_enabled       = true
}

# App Service Plan (Standard SKU or higher is required for deployment slots)
resource "azurerm_service_plan" "asp" {
  count               = var.enable_compute ? 1 : 0
  name                = "asp-${var.project_name}-${var.environment}"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  os_type             = "Linux"
  sku_name            = "S1"
}

# Azure Managed Redis (Replaces retired Azure Cache for Redis)
resource "azurerm_managed_redis" "redis" {
  count               = var.enable_compute ? 1 : 0
  name                = "redis-${var.project_name}-${var.environment}"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku_name            = "Balanced_B0"

  default_database {
    access_keys_authentication_enabled = true
  }
}

# Azure Database for PostgreSQL Flexible Server
resource "azurerm_postgresql_flexible_server" "postgres" {
  name                   = "postgres-${var.project_name}-${var.environment}"
  resource_group_name    = azurerm_resource_group.rg.name
  location               = azurerm_resource_group.rg.location
  version                = "15"
  administrator_login    = var.postgres_admin_username
  administrator_password = var.postgres_admin_password
  storage_mb             = 32768
  sku_name               = "B_Standard_B1ms"
  zone                   = "1"
}

# Database inside PostgreSQL Server
resource "azurerm_postgresql_flexible_server_database" "db" {
  name      = "supply_db"
  server_id = azurerm_postgresql_flexible_server.postgres.id
  collation = "en_US.utf8"
  charset   = "utf8"
}

# Allow Azure Services to access the PostgreSQL DB
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_azure_services" {
  name             = "allow-azure-services"
  server_id        = azurerm_postgresql_flexible_server.postgres.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# Backend App Service (Production)
resource "azurerm_linux_web_app" "backend_api" {
  count               = var.enable_compute ? 1 : 0
  name                = "pg-enterprise-supply-api"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  service_plan_id     = azurerm_service_plan.asp[0].id

  site_config {
    always_on = true
    application_stack {
      docker_image_name        = "backend:latest"
      docker_registry_url      = "https://${azurerm_container_registry.acr.login_server}"
      docker_registry_username = azurerm_container_registry.acr.admin_username
      docker_registry_password = azurerm_container_registry.acr.admin_password
    }
  }

  app_settings = {
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.db.name}?sslmode=require"
    "SPRING_DATASOURCE_USERNAME"          = var.postgres_admin_username
    "SPRING_DATASOURCE_PASSWORD"          = var.postgres_admin_password
    "SPRING_REDIS_HOST"                   = azurerm_managed_redis.redis[0].hostname
    "SPRING_REDIS_PORT"                   = tostring(azurerm_managed_redis.redis[0].default_database[0].port)
    "SPRING_REDIS_PASSWORD"               = azurerm_managed_redis.redis[0].default_database[0].primary_access_key
    "SPRING_REDIS_SSL_ENABLED"            = "true"
    "SPRING_CACHE_TYPE"                   = "redis"
    "WEBSITES_PORT"                       = "8080"
    "WEBSITES_CONTAINER_START_TIME_LIMIT" = "1800"
  }
}

# Backend App Service - Staging Slot
resource "azurerm_linux_web_app_slot" "backend_api_staging" {
  count          = var.enable_compute ? 1 : 0
  name           = "staging"
  app_service_id = azurerm_linux_web_app.backend_api[0].id

  site_config {
    always_on = true
    application_stack {
      docker_image_name        = "backend:latest"
      docker_registry_url      = "https://${azurerm_container_registry.acr.login_server}"
      docker_registry_username = azurerm_container_registry.acr.admin_username
      docker_registry_password = azurerm_container_registry.acr.admin_password
    }
  }

  app_settings = {
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.db.name}?sslmode=require"
    "SPRING_DATASOURCE_USERNAME"          = var.postgres_admin_username
    "SPRING_DATASOURCE_PASSWORD"          = var.postgres_admin_password
    "SPRING_REDIS_HOST"                   = azurerm_managed_redis.redis[0].hostname
    "SPRING_REDIS_PORT"                   = tostring(azurerm_managed_redis.redis[0].default_database[0].port)
    "SPRING_REDIS_PASSWORD"               = azurerm_managed_redis.redis[0].default_database[0].primary_access_key
    "SPRING_REDIS_SSL_ENABLED"            = "true"
    "SPRING_CACHE_TYPE"                   = "redis"
    "WEBSITES_PORT"                       = "8080"
    "WEBSITES_CONTAINER_START_TIME_LIMIT" = "1800"
  }
}


