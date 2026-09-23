# Deployment cost considerations

The current Terraform configuration uses a Basic container registry, an S1 App Service plan, a Balanced_B0 Managed Redis instance, and a B_Standard_B1ms PostgreSQL server. The production app and staging slot share the plan and backing services.

The combined image serves the frontend through Spring Boot, so the current Terraform configuration does not provision a separate frontend Web App. Its JVM heap ceiling is 768 MiB. Heap size is only part of process memory; this setting does not guarantee that two containers fit the plan under load.

Previous versions of this document asserted monthly savings, memory savings, zero-downtime releases, and successful benchmark results without retaining the evidence needed to reproduce them. Those claims have been removed. Estimate costs for the actual region, subscription, uptime, storage, backup, and network usage, and retain dated billing exports before claiming savings.

`bin/suspend.sh` sets `enable_compute=false`, destroying the Web App, staging slot, App Service plan, and Redis instance, then stopping PostgreSQL compute. This loses Redis data and interrupts service. Database storage and the container registry remain billable. PostgreSQL's service-managed stop duration is limited; a stopped server can resume automatically. See the [operations guide](deployment-and-operations.md) before using these scripts.

No cloud change or cost measurement was performed as part of the code review.
