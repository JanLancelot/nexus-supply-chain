# 📋 Requirements & Scope

**Project Name:** Nexus Supply Chain

**Target Environment:** Internal IT Simulation

**Document Classification:** Internal Technical Specifications

---

## 1. System Vision & Business Case

This platform acts as an internal operations hub designed to eliminate inventory blind spots, mitigate manual procurement tracking errors, and guarantee absolute operational accountability. The application enforces systemic guardrails to ensure that data entry anomalies (such as negative stock counts or illegal transaction pathways) are systematically blocked before hitting corporate persistence systems.

---

## 2. Target User Personas & Role-Based Access Control (RBAC)

The application secures business workflows by evaluating cryptographic scopes embedded within user session tokens. No anonymous traffic is permitted to execute data mutations.

```text
               ┌──────────────────────────────────────┐
               │       Authenticated Identity         │
               └──────────────────┬───────────────────┘
                                  │
         ┌────────────────────────┴────────────────────────┐
         ▼                                                 ▼
┌─────────────────────────────────┐               ┌─────────────────────────────────┐
│           ROLE_STAFF            │               │           ROLE_ADMIN            │
├─────────────────────────────────┤               ├─────────────────────────────────┤
│ • Read-Only Catalog Access      │               │ • Full Product Catalog CRUD     │
│ • View Low-Stock Discrepancies  │               │ • Override Global Stock Levels  │
│ • Create Purchase Orders (Draft)│               │ • Approve & Ship System Orders  │
└─────────────────────────────────┘               └─────────────────────────────────┘

```

### 2.1 `ROLE_STAFF` (Inventory Operator)

* **Objective:** Handles high-volume, localized day-to-day material management, receiving, and procurement requests.
* **Functional Scope:**
* Read-only visibility across product balances, SKU lists, and warehouse counts.
* Authority to create structural Purchase Orders restricted specifically to a `DRAFT` status.
* Can view automated metrics indicating inventory levels passing beneath structural safety margins.



### 2.2 `ROLE_ADMIN` (Supply Chain Manager)

* **Objective:** Oversees macro-level warehouse optimization, budget approvals, policy changes, and forensic error recovery.
* **Functional Scope:**
* Full data lifecycle ownership over product definitions (Create, Read, Update, Delete).
* Direct execution authority to modify existing purchase orders across all subsequent lifecycle pathways (`APPROVED`, `SHIPPED`).
* Dedicated read permissions to scan historical transaction trails captured across the immutable audit log table.



---

## 3. Detailed Functional Requirements

### 3.1 Product Catalog & Inventory Boundaries (`REQ-INT`)

* **REQ-INT-01: Global Identification (SKU)**
Every distinct product must be tracked via an alphanumeric Stock Keeping Unit (SKU) structured according to corporate formatting specifications (e.g., `PG-DETERG-001`). SKUs must be unique, non-nullable, and indexed for rapid lookup performance.
* **REQ-INT-02: Safety stock Threshold alerts**
The system must calculate safety stock positions dynamically during transactional execution. If a product's current `stock_quantity` falls strictly below its configured `reorder_level`, an automated logical tag `LOW_STOCK` must evaluate to `true` on search indices.
* **REQ-INT-03: Reason-Coded Stock Overrides**
Manual inventory adjustments executed by administrative personnel must require a string payload detailing a valid operational reason code (e.g., `CYCLIC_COUNT_DISCREPANCY`, `DAMAGED_GOODS_SCRAP`). Adjustments lacking structural explanations must be rejected at the API layer.

### 3.2 Purchase Order Domain & State Lifecycle Engine (`REQ-ORD`)

* **REQ-ORD-01: Deterministic Lifecycle Transitions**
The order processing module must govern data modifications according to a finite state machine. Allowed workflows must adhere strictly to the following transition criteria:

```text
[ DRAFT ] ──> [ PENDING_APPROVAL ] ──> [ APPROVED ] ──> [ SHIPPED ] ──> [ DELIVERED ]
                       │                                    │
                       └────────────────────────────────────┴───────────> [ CANCELLED ]

```

* **REQ-ORD-02: Privilege Gates on Lifecycle Advancement**
An identity authenticated under a `ROLE_STAFF` credential can only transition an order from `DRAFT` to `PENDING_APPROVAL`. Advancing an order path into `APPROVED` or `SHIPPED` scopes requires explicit administrative verification (`ROLE_ADMIN`).
* **REQ-ORD-03: Inventory Balancing Transactions**
Upon moving an order status token into the `DELIVERED` termination pool, the backend system must parse the items inside that purchase order payload, match the structural item allocations, and execute a mathematically precise increment against the live product inventory values. This execution cycle must operate inside an **isolated atomic database transaction**. If any target product record update fails, the entire order state transition is rolled back to preserve consistency.

### 3.3 Forensic System Audit Logging (`REQ-AUD`)

* **REQ-AUD-01: Auto-Triggered Capture Strategy**
Any modification event involving rows within the core inventory tracking matrices (`products`, `orders`) must spark a reactive interception hook that computes an exact structural capture payload before writing changes to the underlying storage engines.
* **REQ-AUD-02: Structural Anatomy of an Audit Record**
Every captured audit trail log entry must structurally preserve the following dataset values:
* Unique transaction identity keys (`BIGINT`).
* Target entity classification and tracking keys (e.g., `EntityName: Product`, `EntityID: UUID`).
* Descriptive verb action classification (e.g., `ACTION_MANUAL_ADJUSTMENT`).
* High-fidelity snapshots mapping the old configuration state versus the incoming structural values (`old_value` string, `new_value` string).
* System Actor Identification keys verifying the individual responsible for executing the change.
* System execution microsecond timestamp tracking keys (`TIMESTAMP WITH TIME ZONE`).


* **REQ-AUD-03: System Immutability Enforcement**
The audit infrastructure database architecture layer must possess zero exposure mappings matching HTTP destructive verbs (`PUT`, `DELETE`). Any script attempting structural schema alterations or database truncation updates inside this space must immediately trigger a fatal security context execution exception.

---

## 4. Non-Functional Requirements (NFRs)

### 4.1 Security Boundaries & Data Hardening

* **NFR-SEC-01: At-Rest Cipher Protections**
Plain text credential entries passing into systemic processing buffers must be intercepted and scrambled natively using salt-fortified cryptographic hashing implementations via **BCrypt** algorithms with an execution cost parameter matching or exceeding factor 10.
* **NFR-SEC-02: Cryptographic Bearer Isolation**
Transport channel validations across decoupled network architectures must utilize stateless **JSON Web Tokens (JWT)** generated via explicit signature authentication keys. Active token validity lifecycles must be forced to hard-expire precisely 60 minutes post-issuance.

### 4.2 Data Validation Invariants

* **NFR-VAL-01: Request Context Validation Gates**
Incoming request structures targeting service execution points must pass comprehensive field assertions within framework controller filters before routing execution logic into internal domain layers. Submissions with validation anomalies (such as negative stock quantities, empty SKUs, or strings exceeding database storage limits) must immediately fail execution, returning standardized API validation payloads.

### 4.3 Reliability & Operational Observability

* **NFR-OPS-01: Base System Instrumentation Checkpoints**
The server framework must maintain real-time telemetry points via standardized metrics endpoints (e.g., `/health`). The health monitoring framework must explicitly verify active database connection viability and return status confirmations matching clean infrastructure operations.

---

## 5. Scope Boundaries (Out of Scope for Phase 1)

To ensure rapid execution and clean architecture, the following enterprise capabilities are intentionally deferred to subsequent operational iterations:

* **Multi-Warehouse Distribution Overlays:** All stock configurations are evaluated under a single centralized logistical facility model.
* **Third-Party Payment Gateways:** Financial reconciliations are managed strictly as a structural accounting mechanism within internal registries.
* **Real-time Logistics Telemetry (GPS):** Order tracking is determined purely by state shifts rather than localized coordinate updates.