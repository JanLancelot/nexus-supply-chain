# Mock provider: these tests cannot create or modify Azure resources.
mock_provider "azurerm" {
  mock_resource "azurerm_postgresql_flexible_server" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-group/providers/Microsoft.DBforPostgreSQL/flexibleServers/test-db"
    }
  }
  mock_resource "azurerm_service_plan" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-group/providers/Microsoft.Web/serverFarms/test-plan"
    }
  }
  mock_resource "azurerm_container_registry" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-group/providers/Microsoft.ContainerRegistry/registries/testregistry"
    }
  }
  mock_resource "azurerm_linux_web_app" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-group/providers/Microsoft.Web/sites/test-app"
    }
  }
  mock_resource "azurerm_linux_web_app_slot" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-group/providers/Microsoft.Web/sites/test-app/slots/staging"
    }
  }
  mock_resource "azurerm_user_assigned_identity" {
    defaults = {
      id           = "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/test-group/providers/Microsoft.ManagedIdentity/userAssignedIdentities/test-ci"
      principal_id = "00000000-0000-0000-0000-000000000002"
    }
  }
}

variables {
  production_image_tag            = "590415c"
  postgres_admin_password         = "test-production-db-password"
  staging_postgres_admin_password = "test-staging-db-password"
  jwt_secret                      = "test-production-jwt-key-at-least-32-characters"
  staging_jwt_secret              = "test-staging-jwt-key-at-least-32-characters"
}

run "staging_has_independent_authority_and_datastores" {
  command = apply

  assert {
    condition = (
      azurerm_linux_web_app_slot.backend_api_staging[0].app_settings["SPRING_DATASOURCE_URL"] != azurerm_linux_web_app.backend_api[0].app_settings["SPRING_DATASOURCE_URL"] &&
      azurerm_linux_web_app_slot.backend_api_staging[0].app_settings["SPRING_REDIS_HOST"] != azurerm_linux_web_app.backend_api[0].app_settings["SPRING_REDIS_HOST"] &&
      azurerm_linux_web_app_slot.backend_api_staging[0].app_settings["JWT_SECRET"] != azurerm_linux_web_app.backend_api[0].app_settings["JWT_SECRET"]
    )
    error_message = "A staging request must never use production data or signing authority."
  }
  assert {
    condition     = contains(azurerm_linux_web_app.backend_api[0].sticky_settings[0].app_setting_names, "SPRING_DATASOURCE_URL") && contains(azurerm_linux_web_app.backend_api[0].sticky_settings[0].app_setting_names, "JWT_SECRET")
    error_message = "Swapping a slot must retain each environment's data and signing authority."
  }
  assert {
    condition     = azurerm_role_assignment.github_staging_deploy[0].scope == azurerm_linux_web_app_slot.backend_api_staging[0].id
    error_message = "GitHub must receive write permission only on the staging slot."
  }
}

run "reused_staging_secret_is_rejected" {
  command = plan
  variables {
    staging_jwt_secret = "test-production-jwt-key-at-least-32-characters"
  }
  expect_failures = [azurerm_linux_web_app_slot.backend_api_staging]
}

run "monitoring_destinations_remain_slot_specific" {
  command = plan
  variables {
    monitoring_grafana_url         = "https://metrics.example.test/production"
    staging_monitoring_grafana_url = "https://metrics.example.test/staging"
  }
  assert {
    condition = (
      azurerm_linux_web_app.backend_api[0].app_settings["APP_MONITORING_GRAFANA_URL"] == "https://metrics.example.test/production" &&
      azurerm_linux_web_app_slot.backend_api_staging[0].app_settings["APP_MONITORING_GRAFANA_URL"] == "https://metrics.example.test/staging" &&
      azurerm_linux_web_app.backend_api[0].app_settings["APP_MONITORING_ENVIRONMENT"] != azurerm_linux_web_app_slot.backend_api_staging[0].app_settings["APP_MONITORING_ENVIRONMENT"] &&
      contains(azurerm_linux_web_app.backend_api[0].sticky_settings[0].app_setting_names, "APP_MONITORING_GRAFANA_URL") &&
      contains(azurerm_linux_web_app.backend_api[0].sticky_settings[0].app_setting_names, "APP_MONITORING_ENVIRONMENT")
    )
    error_message = "Monitoring links and metric environments must stay attached to their deployment slot."
  }
}

run "insecure_grafana_destination_is_rejected" {
  command = plan
  variables {
    monitoring_grafana_url = "http://metrics.example.test"
  }
  expect_failures = [var.monitoring_grafana_url]
}
