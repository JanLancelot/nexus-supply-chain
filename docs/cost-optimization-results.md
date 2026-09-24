# Deployment cost considerations

The current Terraform configuration uses a Basic container registry, an S1 App Service plan, two Balanced_B0 Managed Redis instances, and two B_Standard_B1ms PostgreSQL servers. Production and staging share the App Service plan but have separate backing services. Isolation increases storage and service costs; no savings claim is made.

The combined image serves the frontend through Spring Boot, so the current Terraform configuration does not provision a separate frontend Web App. Its JVM heap ceiling is 768 MiB. Heap size is only part of process memory; this setting does not guarantee that two containers fit the plan under load.

Estimate costs using the deployment region, subscription pricing, uptime, storage, backups, and network usage. Compare dated billing exports over equivalent periods to measure savings.

`bin/suspend.sh` sets `enable_compute=false`, destroying the Web App, staging slot, App Service plan, and both Redis instances, then stopping both PostgreSQL servers. This loses Redis data and interrupts service. Database storage and the container registry remain billable. PostgreSQL's service-managed stop duration is limited; a stopped server can resume automatically. See the [operations guide](deployment-and-operations.md) before using these scripts.
