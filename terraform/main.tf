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

# Azure Managed Redis
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
  name                = var.web_app_name
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
      docker_image_name   = "backend:${var.production_image_tag}"
      docker_registry_url = "https://${azurerm_container_registry.acr.login_server}"
    }
  }

  sticky_settings {
    app_setting_names = ["JWT_SECRET", "APP_BOOTSTRAP_ADMIN_EMAIL", "APP_BOOTSTRAP_ADMIN_PASSWORD",
      "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
      "SPRING_REDIS_HOST", "SPRING_REDIS_PORT", "SPRING_REDIS_PASSWORD", "SPRING_REDIS_SSL_ENABLED",
    "APP_MONITORING_GRAFANA_URL", "APP_MONITORING_ENVIRONMENT"]
  }

  app_settings = {
    "APP_MONITORING_GRAFANA_URL"          = var.monitoring_grafana_url
    "APP_MONITORING_ENVIRONMENT"          = var.environment
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
      docker_image_name   = "backend:${coalesce(var.staging_image_tag, var.production_image_tag)}"
      docker_registry_url = "https://${azurerm_container_registry.acr.login_server}"
    }
  }

  lifecycle {
    # CI owns the staged revision after initial provisioning.
    ignore_changes = [site_config[0].application_stack[0].docker_image_name]
    precondition {
      condition     = var.staging_jwt_secret != var.jwt_secret && var.staging_postgres_admin_password != var.postgres_admin_password
      error_message = "Staging must use a distinct JWT key and database password."
    }
  }

  app_settings = {
    "APP_MONITORING_GRAFANA_URL"          = var.staging_monitoring_grafana_url
    "APP_MONITORING_ENVIRONMENT"          = "${var.environment}-staging"
    "JWT_SECRET"                          = var.staging_jwt_secret
    "APP_BOOTSTRAP_ADMIN_EMAIL"           = var.staging_bootstrap_admin_email
    "APP_BOOTSTRAP_ADMIN_PASSWORD"        = var.staging_bootstrap_admin_password
    "APP_SEED_DEMO_DATA"                  = "false"
    "SPRING_DATASOURCE_URL"               = "jdbc:postgresql://${azurerm_postgresql_flexible_server.staging_postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.staging_db.name}?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
    "SPRING_DATASOURCE_USERNAME"          = var.postgres_admin_username
    "SPRING_DATASOURCE_PASSWORD"          = var.staging_postgres_admin_password
    "SPRING_REDIS_HOST"                   = azurerm_managed_redis.staging_redis[0].hostname
    "SPRING_REDIS_PORT"                   = tostring(azurerm_managed_redis.staging_redis[0].default_database[0].port)
    "SPRING_REDIS_PASSWORD"               = azurerm_managed_redis.staging_redis[0].default_database[0].primary_access_key
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

# Staging has its own datastore and authentication authority. Its SQL administrator
# cannot connect to the production database server.
resource "azurerm_postgresql_flexible_server" "staging_postgres" {
  name                   = "postgres-${var.project_name}-${var.environment}-staging"
  resource_group_name    = azurerm_resource_group.rg.name
  location               = azurerm_resource_group.rg.location
  version                = "15"
  administrator_login    = var.postgres_admin_username
  administrator_password = var.staging_postgres_admin_password
  storage_mb             = 32768
  sku_name               = "B_Standard_B1ms"
  zone                   = "1"
}

resource "azurerm_postgresql_flexible_server_database" "staging_db" {
  name      = "supply_db"
  server_id = azurerm_postgresql_flexible_server.staging_postgres.id
  collation = "en_US.utf8"
  charset   = "utf8"
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "staging_allowed_ips" {
  for_each         = var.staging_postgres_allowed_ips
  name             = each.key
  server_id        = azurerm_postgresql_flexible_server.staging_postgres.id
  start_ip_address = each.value
  end_ip_address   = each.value
}

resource "azurerm_managed_redis" "staging_redis" {
  count               = var.enable_compute ? 1 : 0
  name                = "redis-${var.project_name}-${var.environment}-staging"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku_name            = "Balanced_B0"
  default_database {
    access_keys_authentication_enabled = true
  }
}

# GitHub's main-branch deployment identity can push images and update staging.
resource "azurerm_user_assigned_identity" "github_deploy" {
  name                = "github-${var.project_name}-${var.environment}"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
}

resource "azurerm_federated_identity_credential" "github_main" {
  name                = "github-main"
  resource_group_name = azurerm_resource_group.rg.name
  parent_id           = azurerm_user_assigned_identity.github_deploy.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_repository}:ref:refs/heads/main"
}

resource "azurerm_role_assignment" "github_acr_push" {
  scope                = azurerm_container_registry.acr.id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.github_deploy.principal_id
}

resource "azurerm_role_assignment" "github_app_read" {
  count                = var.enable_compute ? 1 : 0
  scope                = azurerm_linux_web_app.backend_api[0].id
  role_definition_name = "Reader"
  principal_id         = azurerm_user_assigned_identity.github_deploy.principal_id
}

resource "azurerm_role_assignment" "github_staging_deploy" {
  count                = var.enable_compute ? 1 : 0
  scope                = azurerm_linux_web_app_slot.backend_api_staging[0].id
  role_definition_name = "Website Contributor"
  principal_id         = azurerm_user_assigned_identity.github_deploy.principal_id
}
