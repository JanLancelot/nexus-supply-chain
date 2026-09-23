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
  admin_enabled       = false
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

# The default is deny-all; supply approved application egress/admin IPs.
resource "azurerm_postgresql_flexible_server_firewall_rule" "allowed_ips" {
  for_each         = var.postgres_allowed_ips
  name             = each.key
  server_id        = azurerm_postgresql_flexible_server.postgres.id
  start_ip_address = each.value
  end_ip_address   = each.value
}

# Backend App Service (Production)
resource "azurerm_linux_web_app" "backend_api" {
  count               = var.enable_compute ? 1 : 0
  name                = "pg-enterprise-supply-api"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  service_plan_id     = azurerm_service_plan.asp[0].id

  identity {
    type = "SystemAssigned"
  }

  https_only                                     = true
  ftp_publish_basic_authentication_enabled       = false
  webdeploy_publish_basic_authentication_enabled = false

  site_config {
    always_on                               = true
    container_registry_use_managed_identity = true
    minimum_tls_version                     = "1.2"
    scm_minimum_tls_version                 = "1.2"
    ftps_state                              = "Disabled"
    application_stack {
      docker_image_name   = "backend:latest"
      docker_registry_url = "https://${azurerm_container_registry.acr.login_server}"
    }
  }

  app_settings = {
    "JWT_SECRET"                          = var.jwt_secret
    "APP_BOOTSTRAP_ADMIN_EMAIL"           = var.bootstrap_admin_email
    "APP_BOOTSTRAP_ADMIN_PASSWORD"        = var.bootstrap_admin_password
    "APP_SEED_DEMO_DATA"                  = "false"
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.db.name}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
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

  identity {
    type = "SystemAssigned"
  }

  https_only                                     = true
  ftp_publish_basic_authentication_enabled       = false
  webdeploy_publish_basic_authentication_enabled = false

  site_config {
    always_on                               = true
    container_registry_use_managed_identity = true
    minimum_tls_version                     = "1.2"
    scm_minimum_tls_version                 = "1.2"
    ftps_state                              = "Disabled"
    application_stack {
      docker_image_name   = "backend:latest"
      docker_registry_url = "https://${azurerm_container_registry.acr.login_server}"
    }
  }

  app_settings = {
    "JWT_SECRET"                          = var.jwt_secret
    "APP_BOOTSTRAP_ADMIN_EMAIL"           = var.bootstrap_admin_email
    "APP_BOOTSTRAP_ADMIN_PASSWORD"        = var.bootstrap_admin_password
    "APP_SEED_DEMO_DATA"                  = "false"
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.db.name}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
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



resource "azurerm_role_assignment" "production_acr_pull" {
  count                = var.enable_compute ? 1 : 0
  scope                = azurerm_container_registry.acr.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_linux_web_app.backend_api[0].identity[0].principal_id
}

resource "azurerm_role_assignment" "staging_acr_pull" {
  count                = var.enable_compute ? 1 : 0
  scope                = azurerm_container_registry.acr.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_linux_web_app_slot.backend_api_staging[0].identity[0].principal_id
}
