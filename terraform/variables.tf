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
  default     = "pgsupplyregistry"
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
  description = "Named single IPv4 addresses allowed to access PostgreSQL. Include production and staging outbound IPs."
  validation {
    condition     = alltrue([for ip in values(var.postgres_allowed_ips) : can(cidrnetmask("${ip}/32")) && ip != "0.0.0.0"])
    error_message = "Provide individual IPv4 addresses; the all-Azure-services address 0.0.0.0 is forbidden."
  }
}
