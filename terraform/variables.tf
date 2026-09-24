variable "azure_subscription_id" {
  type        = string
  description = "The Azure Subscription ID to deploy resources into."
  default     = ""
}

variable "azure_tenant_id" {
  type        = string
  description = "The Azure Tenant ID."
  default     = ""
}

variable "location" {
  type        = string
  description = "The Azure Region to deploy resources."
  default     = "East US"
}

variable "project_name" {
  type        = string
  description = "A prefix for naming resources."
  default     = "nexus-supply"
}

variable "environment" {
  type        = string
  description = "The environment suffix (e.g. dev, staging, prod)."
  default     = "prod"
}

variable "acr_name" {
  type        = string
  description = "The name of the Azure Container Registry. Must be globally unique."
  default     = "nexussupplyregistry"
}

variable "web_app_name" {
  type        = string
  description = "The production Web App name. Preserve the deployed name when upgrading existing infrastructure."
  default     = "nexus-supply-api"
}

variable "postgres_admin_username" {
  type        = string
  description = "The administrator username for the PostgreSQL database server."
  default     = "enterprise_admin"
}

variable "postgres_admin_password" {
  type        = string
  description = "The administrator password for the PostgreSQL database server."
  sensitive   = true
}

variable "enable_compute" {
  type        = bool
  description = "Enable compute resources (App Service Plan, App Services, Managed Redis)."
  default     = true
}


variable "jwt_secret" {
  type        = string
  sensitive   = true
  description = "JWT signing key, at least 32 random ASCII characters. Rotate the previously committed key."
  validation {
    condition     = length(var.jwt_secret) >= 32
    error_message = "jwt_secret must contain at least 32 characters."
  }
}

variable "bootstrap_admin_email" {
  type        = string
  default     = ""
  description = "Email for initial admin provisioning; remove after the first successful startup."
}

variable "bootstrap_admin_password" {
  type        = string
  sensitive   = true
  default     = ""
  description = "Initial admin password; remove after the account has been created."
}

variable "postgres_allowed_ips" {
  type        = map(string)
  default     = {}
  description = "Named single IPv4 addresses allowed to access PostgreSQL. Include only approved production outbound and administrator IPs."
  validation {
    condition     = alltrue([for ip in values(var.postgres_allowed_ips) : can(cidrnetmask("${ip}/32")) && ip != "0.0.0.0"])
    error_message = "Provide individual IPv4 addresses; the all-Azure-services address 0.0.0.0 is forbidden."
  }
}

variable "staging_postgres_admin_password" {
  type        = string
  sensitive   = true
  description = "Password for the separate staging database server; must differ from production."
}

variable "staging_jwt_secret" {
  type        = string
  sensitive   = true
  description = "Independent staging signing key; must differ from production."
  validation {
    condition     = length(var.staging_jwt_secret) >= 32
    error_message = "staging_jwt_secret must contain at least 32 characters."
  }
}

variable "staging_bootstrap_admin_email" {
  type        = string
  default     = ""
  description = "Email for initial staging administrator provisioning."
}

variable "staging_bootstrap_admin_password" {
  type        = string
  sensitive   = true
  default     = ""
  description = "Initial staging admin password; remove after provisioning."
}

variable "staging_postgres_allowed_ips" {
  type        = map(string)
  default     = {}
  description = "Named staging outbound/admin IPv4 addresses; empty denies client access."
  validation {
    condition     = alltrue([for ip in values(var.staging_postgres_allowed_ips) : can(cidrnetmask("${ip}/32")) && ip != "0.0.0.0"])
    error_message = "Provide individual IPv4 addresses, never the all-Azure-services address."
  }
}

variable "github_repository" {
  type        = string
  default     = "JanLancelot/nexus-supply-chain"
  description = "Exact owner/repository allowed to use the main-branch deployment identity."
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be an owner/repository name."
  }
}

variable "production_image_tag" {
  type        = string
  description = "Existing backend commit tag pinned for production; never latest."
  validation {
    condition     = can(regex("^[a-f0-9]{7,40}$", var.production_image_tag))
    error_message = "production_image_tag must be a published Git commit SHA."
  }
}

variable "staging_image_tag" {
  type        = string
  default     = null
  description = "Existing commit tag for initial staging bootstrap; defaults to the pinned production image. CI owns subsequent staging image changes."
  validation {
    condition     = var.staging_image_tag == null ? true : can(regex("^[a-f0-9]{7,40}$", var.staging_image_tag))
    error_message = "staging_image_tag must be a published Git commit SHA."
  }
}
