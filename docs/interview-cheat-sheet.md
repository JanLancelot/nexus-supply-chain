# 🎯 Tech Interview Cheat Sheet & STAR Prep Guide

This guide breaks down the engineering achievements on your resume into simple, conversational talking points. Use this to prepare for HR screeners, hiring managers, and deep-dive technical panels.

---

## 💡 Quick Tips for the Interview
* **Talk about Trade-offs:** Senior engineers don't just write code; they weigh options. When explaining why you consolidated the architecture, emphasize that you traded decoupled release lifecycles for cost savings and stability, and explain how you mitigated the downsides.
* **Keep it STAR-focused:** **S**ituation (Context/Problem), **T**ask (Your Responsibility), **A**ction (What *you* did), **R**esult (Quantified metrics).
* **Reference the Code:** If they ask you to show code, open the files linked below during the screen share.

---

## 🚂 Claim 1: Automated Procurement & Duplicate Safeguards

> **Resume Bullet:** *Engineered an enterprise-grade supply chain platform featuring an automated procurement engine that dynamically manages purchase order lifecycles and implements data-safeguards to eliminate duplicate transactions.*

### ⏱️ 30-Second Elevator Pitch
> *"I built a system that monitors inventory levels in real-time. When stock drops below a certain threshold, the system automatically writes a purchase order. To prevent generating duplicate orders during high-traffic sales, I built a hybrid two-tier rate-limiting system: a fast, local in-memory pre-filter to drop immediate bursts, and a Redis-based distributed lock to coordinate across scaled application instances. It also queries the database to block new orders if there's already an active open order. I also enforced a validated state machine to ensure orders move strictly from Draft to Delivered."*

### ⭐ STAR Breakdown
* **Situation:** During high concurrent operations, product inventory updates occurred in rapid bursts. If multiple updates fired at once, they would trigger multiple simultaneous auto-replenishment requests, creating duplicate draft orders in the database.
* **Task:** Build an automated replenishment engine with strict state validation and safeguards to ensure exactly one purchase order is generated per replenishment event.
* **Action:** 
  1. Implemented an event-driven listener in [AutoReplenishmentService.java](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/backend/src/main/java/com/pg/supplychain/service/AutoReplenishmentService.java) that reacts to stock changes.
  2. Built **Safeguard 1 (Hybrid Cooldown):** Used a local `ConcurrentHashMap` to drop immediate duplicate events instantly in memory (saving network trips), followed by an atomic Redis `setIfAbsent` lock with a 60-second TTL to ensure cluster-wide coordination.
  3. Built **Safeguard 2 (Active Order Scan):** Queries the database to check if there is an active, open order for the product. If one exists, the request is discarded.
  4. Built a **Finite State Machine (FSM):** Enforced valid status transitions in [OrderService.java](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/backend/src/main/java/com/pg/supplychain/service/OrderService.java) (e.g., only admins can approve and ship orders; staff can only create drafts).
* **Result:** Zero duplicate orders generated during high-load tests; secure, automated procurement logs with immutable forensic audit history.

### ❓ Probable Follow-Up Questions
* **Q: Why did you use a hybrid cooldown strategy instead of just in-memory or Redis?**
  * *Answer:* "The hybrid strategy gives us the best of both worlds: speed and cluster safety. The local in-memory map acts as a high-speed CPU filter, dropping concurrent burst events instantly on the same thread before we spend network time or connection pools calling Redis. If the local check passes, we call Redis to acquire a distributed lock, ensuring coordination if the application is scaled out horizontally across multiple VM instances. If Redis goes down, the system is resilient and gracefully falls back to the local memory lock."
* **Q: What happens if the server restarts? Won't the local cooldown map be wiped?**
  * *Answer:* "Yes, but Safeguard 2 (the database check) serves as our safety net. If a server restarts and the local map is cleared, the system will execute the database query, find the existing open order, and gracefully skip creating a duplicate anyway."

---

## 📉 Claim 2: Cloud Cost Optimization & Container Consolidation

> **Resume Bullet:** *Optimized cloud infrastructure costs by 50% and permanently resolved production runtime instability by consolidating the architecture into a single container deployment.*

### ⏱️ 30-Second Elevator Pitch
> *"Our staging and production environments were running the React frontend and Spring Boot API in separate Azure Web App containers, each with its own staging slot. This meant 4 containers sharing a restricted 1.75 GB RAM instance, causing constant out-of-memory crashes. Instead of spending double the money to upgrade the hosting plan, I consolidated the architecture by serving the React static files directly from the Spring Boot container. This cut our container count in half, stabilized memory usage, resolved CORS issues, and saved us 50% in compute fees."*

### ⭐ STAR Breakdown
* **Situation:** The application ran two distinct Web Apps (Frontend Nginx and Backend Spring Boot) with deployment slots on an Azure `S1` plan. This meant **4 running containers** sharing **1.75 GB of RAM**. The memory overhead caused frequent OOM restarts. SRE proposed upgrading to an `S2` plan (~$140/mo).
* **Task:** Permanently stabilize the staging and production environments without exceeding the S1 hosting budget (~$70/mo).
* **Action:**
  1. Modified the [Dockerfile](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/backend/Dockerfile) to build the React code first, then copy the static assets into Spring Boot's resource directory before packaging.
  2. Tuned the JVM heap limits using `-Xmx768m` to guarantee that the single container safely runs under the 1.75 GB ceiling.
  3. Simplified frontend API requests to relative URLs (`/api/v1`) since they share the same origin, resolving all CORS configurations.
  4. Rightsized backing services (downgraded Redis to B0 and ACR to Basic) and wrote automated scripts ([suspend.sh](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/bin/suspend.sh)) to stop resource consumption when not in use.
* **Result:** Saved **$122.00/month** in direct cloud hosting costs (a 50% reduction in compute) and achieved 100% environment stability with 0 OOM restarts.

### ❓ Probable Follow-Up Questions
* **Q: Isn't it a downside that a frontend cosmetic change requires rebuilding the backend?**
  * *Answer:* "Yes, it couples the release cycles. However, we mitigated this by optimizing our build caching in GitHub Actions. Recompiling and verifying the unified jar takes under 3 minutes, and zero-downtime staging deployment slots remove any risk from release deployments."
* **Q: Tomcat is slower at serving static files than Nginx. How did you handle that?**
  * *Answer:* "For low-to-medium traffic, Tomcat handles it easily. For high-scale production, we place a CDN (like Azure CDN or Cloudflare) in front. The CDN caches the static assets at the edge, and Tomcat only handles the dynamic API requests. It gives us the best of both worlds: single-container simplicity and Nginx-like speed."

---

## 🔒 Claim 3: Secure IaC & CI/CD Pipelines

> **Resume Bullet:** *Designed and managed a secure CI/CD pipeline using Terraform and GitHub Actions to deliver incremental code changes frequently and reliably via automated, zero-downtime system deployments.*

### ⏱️ 30-Second Elevator Pitch
> *"I set up our CI/CD pipeline in GitHub Actions, using Terraform to define all Azure infrastructure. To keep security tight, I configured the pipeline to authenticate with Azure using OpenID Connect (OIDC) federation, which completely eliminated the need to store long-lived credentials or passwords in GitHub. The pipeline runs our integration tests using isolated database and cache containers, compiles the code, and pushes the image to a staging slot. Once the staging container is verified healthy, we swap slots to make the new version live with zero downtime."*

### ⭐ STAR Breakdown
* **Situation:** The team needed to ship incremental updates frequently, but manual provisioning was prone to errors, and storing raw Azure access keys in GitHub Secrets posed a security risk.
* **Task:** Automate deployment via Infrastructure as Code (IaC) and set up a secure, hands-off pipeline.
* **Action:**
  1. Wrote Terraform templates ([main.tf](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/terraform/main.tf)) to provision the resource group, web app slots, Postgres database, and Redis.
  2. Configured GitHub Actions to spin up ephemeral Postgres and Redis service containers for unit and integration testing.
  3. Integrated **Azure OIDC token authentication** in [ci.yml](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/.github/workflows/ci.yml#L85-L91), trading short-lived federated credentials rather than passwords.
  4. Deployed new builds directly to the `staging` slot of the Web App, warming up the instance before executing slot swaps to avoid service downtime.
* **Result:** Replaced manual infrastructure configuration with 100% automated IaC; deployments are fully hands-off and occur with zero downtime.

### ❓ Probable Follow-Up Questions
* **Q: How does OIDC make the pipeline more secure than storing a client secret?**
  * *Answer:* "With OIDC, GitHub Actions and Azure trust each other directly. When the build runs, GHA requests a temporary JWT token from GitHub, which Azure validates and trades for a short-lived access token. No password or secret is ever saved in GitHub, meaning there is nothing to leak if the repo is compromised."

---

## 📊 Claim 4: High-Load Stress Testing & Scale Validation

> **Resume Bullet:** *Validated system infrastructure reliability under a simulated peak load of 3,000 concurrent virtual users, maintaining a 0.00% error rate while sustaining a throughput of 1,081 requests/sec against a production-scale database of 3.1 million records.*

### ⏱️ 30-Second Elevator Pitch
> *"To prove the platform could handle enterprise demand, I scaled our local database to 3.1 million records. I then wrote a k6 stress testing script that scaled up to 3,000 concurrent virtual users making a realistic mix of read and write requests. By monitoring our database connection pools, memory garbage collection, and CPU usage via Prometheus, I verified that the system sustained a throughput of over 1,081 requests per second with a 0.00% HTTP failure rate and an average response time of about 2 milliseconds."*

### ⭐ STAR Breakdown
* **Situation:** We needed to guarantee the system could handle enterprise-scale transactional volume and identify performance bottlenecks (like database connection queueing or garbage collection pauses) before deploying to production.
* **Task:** Populate a database to production size and run stress tests simulating extreme concurrency.
* **Action:**
  1. Created a scale script ([scale_data.sql](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/docker/scale_data.sql)) to populate the database with **3.1 million records** (100k products, 500k orders, 1M items, 1M audit logs).
  2. Wrote a k6 script ([high-load-stress-test.js](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/load-tests/high-load-stress-test.js)) ramping up to **3,000 concurrent virtual users** executing a 80/20 read/write transaction mix.
  3. Programmed an automated benchmark runner ([run-benchmark.py](file:///Users/janlancelot/Desktop/Projects/nexus-supply-chain/load-tests/run-benchmark.py)) to gather telemetry from Prometheus and print a unified performance report.
* **Result:** Verified system sustained **1,166 req/s** throughput with **0.00% error rate** under peak load. Latency remained low (p50: 0.65ms, p95: 2.97ms) with CPU load peaking under 27% and HikariCP connection queueing staying at zero.

### ❓ Probable Follow-Up Questions
* **Q: Why was your average latency so low (2.09ms) for a database with 3.1M records?**
  * *Answer:* "We achieved this by implementing Redis cache layer for high-read requests (like catalog searches), adding indexes on key query fields, and tuning HikariCP connection pool configurations to avoid connection queue bottlenecks."
