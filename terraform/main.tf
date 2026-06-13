terraform {
  required_version = ">= 1.3.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.0"
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
  sku                 = "Standard"
  admin_enabled       = true
}

# App Service Plan (Standard SKU or higher is required for deployment slots)
resource "azurerm_service_plan" "asp" {
  name                = "asp-${var.project_name}-${var.environment}"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  os_type             = "Linux"
  sku_name            = "S1"
}

# Azure Cache for Redis
resource "azurerm_redis_cache" "redis" {
  name                = "redis-${var.project_name}-${var.environment}"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  capacity            = 0
  family              = "C"
  sku_name            = "Basic"
  non_ssl_port_enabled = false
  minimum_tls_version = "1.2"
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
  name                = "pg-enterprise-supply-api"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  service_plan_id     = azurerm_service_plan.asp.id

  site_config {
    always_on = true
    application_stack {
      docker_image     = "${azurerm_container_registry.acr.login_server}/backend"
      docker_image_tag = "latest"
    }
  }

  app_settings = {
    "DOCKER_REGISTRY_SERVER_URL"          = "https://${azurerm_container_registry.acr.login_server}"
    "DOCKER_REGISTRY_SERVER_USERNAME"     = azurerm_container_registry.acr.admin_username
    "DOCKER_REGISTRY_SERVER_PASSWORD"     = azurerm_container_registry.acr.admin_password
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.db.name}?sslmode=require"
    "SPRING_DATASOURCE_USERNAME"          = var.postgres_admin_username
    "SPRING_DATASOURCE_PASSWORD"          = var.postgres_admin_password
    "SPRING_REDIS_HOST"                   = azurerm_redis_cache.redis.hostname
    "SPRING_REDIS_PORT"                   = tostring(azurerm_redis_cache.redis.ssl_port)
    "SPRING_CACHE_TYPE"                   = "redis"
    "WEBSITES_PORT"                       = "8080"
  }
}

# Backend App Service - Staging Slot
resource "azurerm_linux_web_app_slot" "backend_api_staging" {
  name           = "staging"
  app_service_id = azurerm_linux_web_app.backend_api.id

  site_config {
    always_on = true
    application_stack {
      docker_image     = "${azurerm_container_registry.acr.login_server}/backend"
      docker_image_tag = "latest"
    }
  }

  app_settings = {
    "DOCKER_REGISTRY_SERVER_URL"          = "https://${azurerm_container_registry.acr.login_server}"
    "DOCKER_REGISTRY_SERVER_USERNAME"     = azurerm_container_registry.acr.admin_username
    "DOCKER_REGISTRY_SERVER_PASSWORD"     = azurerm_container_registry.acr.admin_password
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.db.name}?sslmode=require"
    "SPRING_DATASOURCE_USERNAME"          = var.postgres_admin_username
    "SPRING_DATASOURCE_PASSWORD"          = var.postgres_admin_password
    "SPRING_REDIS_HOST"                   = azurerm_redis_cache.redis.hostname
    "SPRING_REDIS_PORT"                   = tostring(azurerm_redis_cache.redis.ssl_port)
    "SPRING_CACHE_TYPE"                   = "redis"
    "WEBSITES_PORT"                       = "8080"
  }
}

# Frontend App Service (Production)
resource "azurerm_linux_web_app" "frontend_ui" {
  name                = "pg-enterprise-supply-ui"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  service_plan_id     = azurerm_service_plan.asp.id

  site_config {
    always_on = true
    application_stack {
      docker_image     = "${azurerm_container_registry.acr.login_server}/frontend"
      docker_image_tag = "latest"
    }
  }

  app_settings = {
    "DOCKER_REGISTRY_SERVER_URL"          = "https://${azurerm_container_registry.acr.login_server}"
    "DOCKER_REGISTRY_SERVER_USERNAME"     = azurerm_container_registry.acr.admin_username
    "DOCKER_REGISTRY_SERVER_PASSWORD"     = azurerm_container_registry.acr.admin_password
    "WEBSITES_PORT"                       = "80"
  }
}

# Frontend App Service - Staging Slot
resource "azurerm_linux_web_app_slot" "frontend_ui_staging" {
  name           = "staging"
  app_service_id = azurerm_linux_web_app.frontend_ui.id

  site_config {
    always_on = true
    application_stack {
      docker_image     = "${azurerm_container_registry.acr.login_server}/frontend"
      docker_image_tag = "latest"
    }
  }

  app_settings = {
    "DOCKER_REGISTRY_SERVER_URL"          = "https://${azurerm_container_registry.acr.login_server}"
    "DOCKER_REGISTRY_SERVER_USERNAME"     = azurerm_container_registry.acr.admin_username
    "DOCKER_REGISTRY_SERVER_PASSWORD"     = azurerm_container_registry.acr.admin_password
    "WEBSITES_PORT"                       = "80"
  }
}
