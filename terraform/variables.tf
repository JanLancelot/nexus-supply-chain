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
