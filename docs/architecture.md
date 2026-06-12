# 🏛️ System Architecture Design

**Project Name:** Nexus Supply Chain

**Document Target:** Technical Interview Panel & Engineering Alignment

**Design Standard:** Simplified C4 Model (Context & Containers)

---

## 1. System Context (C4 Level 1)

The System Context diagram outlines the external boundaries of the Supply Chain Platform, illustrating how distinct corporate personas and upstream logistics infrastructure interact with the core system application.

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        P&G Enterprise Network                          │
│                                                                        │
│  ┌───────────────────────┐                  ┌───────────────────────┐  │
│  │  Logistics Specialist │                  │ Regional Administrator│  │
│  │     (ROLE_STAFF)      │                  │     (ROLE_ADMIN)      │  │
│  └───────────┬───────────┘                  └───────────┬───────────┘  │
│              │                                          │              │
│              └───────────────────┬──────────────────────┘              │
│                                  │ HTTPS / JSON                        │
│                                  ▼                                     │
│                  ┌───────────────────────────────┐                     │
│                  │  Enterprise Supply Chain      │                     │
│                  │  & Operations Platform        │                     │
│                  └───────────────┬───────────────┘                     │
│                                  │ JDBC / TCP                          │
│                                  ▼                                     │
│                  ┌───────────────────────────────┐                     │
│                  │      Corporate Database       │                     │
│                  │       (PostgreSQL 15)         │                     │
│                  └───────────────────────────────┘                     │
└────────────────────────────────────────────────────────────────────────┘

```

---

## 2. Container Architecture (C4 Level 2)

The Container diagram details the high-level technological building blocks that compose the application ecosystem, their individual responsibilities, and how data moves across system boundaries securely.

```text
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ Browser Layer                                                                │
 │   ┌──────────────────────────────────────────────────────────────────────┐   │
 │   │ SPA Container (React + TypeScript + Tailwind CSS)                    │   │
 │   │ Provides stateful inventory monitoring dashboards and order inputs.  │   │
 │   └──────────────────────────────────┬───────────────────────────────────┘   │
 └──────────────────────────────────────┼───────────────────────────────────────┘
                                        │ HTTPS / REST (Port 8080 / JWT Bearer)
                                        ▼
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ Application Server (Spring Boot / .NET Core Execution Context)                │
 │                                                                              │
 │   ┌───────────────────────┐   ┌───────────────────────┐   ┌──────────────┐   │
 │   │ Security Controller   │──>│ Core Business Services│──>│ Persistence  │   │
 │   │  (JWT Interception)   │   │ (State Engine/Auditing)   │   │  (JPA / EF)  │   │
 │   └───────────────────────┘   └───────────────────────┘   └──────┬───────┘   │
 └──────────────────────────────────────────────────────────────────┼───────────┘
                                                                    │ SQL / JDBC
                                                                    ▼
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ Database Management Infrastructure                                           │
 │   ┌──────────────────────────────────────────────────────────────────────┐   │
 │   │ PostgreSQL 15 Engine Container                                       │   │
 │   │ Isolates and persists domain models, relational arrays, & audit logs.│   │
 │   └──────────────────────────────────────────────────────────────────────┘   │
 └──────────────────────────────────────────────────────────────────────────────┘

```

---

## 3. Core Architectural Components

### 3.1 Presentation Layer (Single Page Application)

* **Technology Stack:** React, TypeScript, Tailwind CSS, Axios.
* **Responsibilities:**
* Client-side rendering of inventory analytics dashboards and transactional workflow modules.
* Local session state enforcement via secure token-storage mechanisms.
* Outbound request interception to append bearer authentication structures (`Authorization: Bearer <JWT>`) uniformly to all transactional API requests.



### 3.2 Application Layer (Enterprise REST API Context)

* **Technology Stack:** Java + Spring Boot (or C# + .NET Core equivalent).
* **Responsibilities:**
* **API Security Gate:** Intercepts incoming processing streams, extracts token payloads, validates signatures, and maps security context profiles (`ROLE_STAFF`, `ROLE_ADMIN`) down the execution thread.
* **Domain Logic Execution Engine:** Controls state transition mechanics for orders and ensures that safety-stock data validations trigger when processing operations execute.
* **Transactional Boundary Controller:** Enforces atomic isolation boundaries (`@Transactional`). If sub-queries fail during multicatalog balance mutations, the system executes matching rollbacks to preserve historical database consistency.



### 3.3 Persistence Layer (Relational Data Store)

* **Technology Stack:** PostgreSQL 15.
* **Responsibilities:**
* Structured storage of core transactional state tables.
* Referential integrity maintenance across key domain nodes via foreign-key constraints.
* Append-only ingestion performance optimization for capturing system modification tracking parameters within the `audit_logs` model context.



---

## 4. Key Cross-Cutting Architecture Design Patterns

### 4.1 Token-Based Stateless Authentication Flow

To maximize system throughput and remove state synchronization overloads across application runtimes, user authorization relies on stateless authentication mechanisms.

```text
[ Client SPA ]                  [ API Security Layer ]           [ Database Store ]
      │                                    │                              │
      │ 1. POST /api/v1/auth/login ───────>│                              │
      │                                    │ 2. Query User Entity ───────>│
      │                                    │<─────── Return Record -------│
      │                                    │                              │
      │   Verify BCrypt Hash Integrity ────┘                              │
      │   Generate Signed JWT Payload ─────┐                              │
      │<─ 3. Return Bearer Token (JWT) ────┘                              │
      │                                    │                              │
      │ 4. GET /api/v1/audit (With Token) ─>│                              │
      │                                    │ Validates Signature & Role ──┐│
      │                                    │ Accesses Protected Resource ─┘│
      │<─ 5. Stream Requested Payload ─────│                              │

```

### 4.2 Automated Reactive Auditing Architecture

To satisfy non-functional requirement `REQ-AUD-01` without littering operational service methods with boilerplate persistence logs, the backend leverages a decoupled structural interception routine.

* **The Interceptor Pattern:** The infrastructure maps domain listener hooks (`@EntityListeners` / Interceptors) directly over tracking schema models.
* **The Lifecycle Interception:** When any active transactional operation successfully schedules an insert, update, or delete modification against the `products` or `orders` datasets, the lifecycle framework hooks into the event thread before committing changes to storage.
* **The Logging Payload Capture:** The runtime interceptor routine reads the mutating entity context, matches active metadata against original object snapshots to compile old state vs. new state properties, extracts identity tags from the ambient thread security context, and automatically streams a record to the `audit_logs` entity cache.