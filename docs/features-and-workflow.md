# Nexus Supply Chain — Features & Workflow Guide

> **Internal IT Simulation Platform** | Enterprise Supply Chain Visibility & Operations

---

## Table of Contents

1. [Overview](#overview)
2. [User Roles & Access Control](#user-roles--access-control)
3. [Authentication Workflow](#authentication-workflow)
4. [Feature Modules](#feature-modules)
   - [Operations Dashboard](#1-operations-dashboard-admin-only)
   - [Product Catalog](#2-product-catalog)
   - [Purchase Orders](#3-purchase-orders)
   - [Forensic Audit Logs](#4-forensic-audit-logs-admin-only)
5. [Purchase Order Lifecycle (FSM)](#purchase-order-lifecycle-finite-state-machine)
6. [Automated Audit System](#automated-audit-system)
7. [Notifications](#notifications)
8. [Security & Non-Functional Behaviour](#security--non-functional-behaviour)
9. [API Endpoint Reference](#api-endpoint-reference)

---

## Overview

Nexus Supply Chain is an **internal operations hub** built to eliminate inventory blind spots, prevent manual procurement tracking errors, and guarantee full operational accountability. The platform enforces systematic guardrails — invalid data (negative stock counts, illegal state transitions) is blocked at the API layer before reaching the database.

**Tech Stack**

| Layer | Technology |
|---|---|
| Frontend | React + TypeScript, Tailwind CSS, Axios |
| Backend | Java + Spring Boot |
| Database | PostgreSQL 15 |
| Auth | JWT (HS256), BCrypt password hashing |
| Infrastructure | Docker, Docker Compose, Terraform (Azure) |

---

## User Roles & Access Control

The system enforces **Role-Based Access Control (RBAC)** using cryptographic scopes embedded in JWT tokens. Anonymous access to any data-mutating endpoint is blocked.

| Capability | `ROLE_STAFF` (Inventory Operator) | `ROLE_ADMIN` (Supply Chain Manager) |
|---|:---:|:---:|
| View Product Catalog | ✅ | ✅ |
| Create New Products | ❌ | ✅ |
| Adjust Stock Levels | ❌ | ✅ |
| Create Purchase Orders (Draft) | ✅ | ✅ |
| Submit Orders for Approval | ✅ | ✅ |
| Approve / Ship / Deliver Orders | ❌ | ✅ |
| Cancel Orders | ✅ (Draft only) | ✅ |
| View Operations Dashboard | ❌ | ✅ |
| View Audit Logs | ❌ | ✅ |
| Receive Notifications | ✅ | ✅ |

> [!IMPORTANT]
> On login, **Admins** are redirected to `/dashboard` and **Staff** are redirected to `/catalog`. Staff attempting to access admin-only routes (`/dashboard`, `/audit-logs`) are automatically redirected to `/catalog`.

---

## Authentication Workflow

```
[ Login Page ]
     │
     │  POST /api/v1/auth/login  { email, password }
     ▼
[ Backend Security Layer ]
     │  1. Look up user record by email
     │  2. Verify BCrypt password hash (cost factor ≥ 10)
     │  3. Generate signed JWT (valid for 60 minutes)
     ▼
[ Client SPA ]
     │  Stores JWT in session/local storage
     │  Attaches  Authorization: Bearer <JWT>  to all API requests
     ▼
[ Protected Resources ]
     │  JWT signature validated on every request
     │  Role scopes extracted and enforced per endpoint
```

**Token behaviour:**
- Tokens hard-expire **60 minutes** after issuance.
- Expired or missing tokens return `401 Unauthorized`.
- Valid token with insufficient role returns `403 Forbidden`.

---

## Feature Modules

### 1. Operations Dashboard *(Admin Only)*

**Route:** `/dashboard`
**Access:** `ROLE_ADMIN` only

The dashboard provides a real-time, at-a-glance view of the entire supply chain operation.

#### KPI Cards (Top Row)

| Card | Description |
|---|---|
| **Settled Revenue** | Total monetary value of all orders in `DELIVERED` status |
| **Inventory Value** | Total asset valuation across all catalogued products |
| **Safety Discrepancies** | Count of products currently below their reorder threshold; pulses amber if > 0 |
| **System Orders** | Total count of all registered procurement orders across all statuses |

#### Charts

- **Top Ordered Product Volumes** — Horizontal bar chart ranking products by total units ordered, relative to the highest-volume item.
- **Order State Distribution** — Progress bars showing the count and percentage of orders in each FSM status (Draft, Pending Approval, Approved, Shipped, Delivered, Cancelled).
- **Warehouse Inventory Distribution** — A grid of cards showing unit stock counts per warehouse facility, with proportional fill bars.

A **Refresh Data** button triggers a live re-fetch from the analytics API endpoint.

---

### 2. Product Catalog

**Route:** `/catalog`
**Access:** `ROLE_STAFF` (read-only) and `ROLE_ADMIN` (full CRUD + stock adjustment)

The catalog is the master registry of all inventory items in the system.

#### Viewing & Filtering

- **Search:** Filter products by **SKU code** or **product name** in real time.
- **Category Filter:** Dropdown to narrow results to a specific product category.
- **Stock Status Filter:** Show **All Stock** or **Low Stock Only** — highlights items that have fallen below their configured reorder level.

#### Product Table Columns

| Column | Description |
|---|---|
| **SKU / Code** | Unique alphanumeric stock-keeping unit (e.g. `PG-TIDE-001`) |
| **Product Details** | Product name and category |
| **Warehouse** | The facility this product is assigned to |
| **Price** | Unit price in USD |
| **Stock Status** | Current quantity with a `LOW STOCK` or `NORMAL` badge |
| **Safety Reorder** | The configured reorder threshold level |
| **Actions** | *(Admin only)* Adjust stock button |

#### Admin: Create New Product

Admins can open a modal to register a new product with:
- **SKU Code** *(required, must be unique)*
- **Product Name** *(required)*
- **Unit Price** *(required, > $0)*
- **Category** *(optional)*
- **Warehouse** *(optional)*
- **Reorder Level** *(required, minimum 0)* — the threshold below which `LOW STOCK` triggers

#### Admin: Adjust Stock

Admins can manually override the stock quantity of any product. A **Reason Code** is mandatory:

| Reason Code | Description |
|---|---|
| `CYCLIC_COUNT_DISCREPANCY` | Periodic physical count correction |
| `DAMAGED_GOODS_SCRAP` | Write-off for damaged or scrapped goods |
| `SUPPLIER_SHORTAGE` | Reduction due to supplier delivery shortfall |

> [!WARNING]
> Stock adjustments cannot result in a negative quantity. A positive number adds stock; a negative number scraps it. Every adjustment generates an **immutable audit log entry** automatically.

---

### 3. Purchase Orders

**Route:** `/orders`
**Access:** `ROLE_STAFF` and `ROLE_ADMIN`

This is the core procurement workflow module. It manages the full lifecycle of purchase orders from draft creation through delivery.

#### Order List View

- **Search** orders by order number, supplier name, or destination warehouse.
- **Status Filter** to show orders in a specific lifecycle state.
- Tabular view showing: Order Number, Supplier, Destination Warehouse, Total Amount, and Status Badge.
- Click **Inspect** on any row to drill into the full order details.

#### Create Purchase Order Wizard

Any authenticated user can create a new order (initialized in `DRAFT` status):

1. **Select Supplier** — Choose from the registered supplier list.
2. **Select Destination Facility (Warehouse)** — The warehouse that will receive the goods.
3. **Add Line Items** — Select products by SKU and specify quantities. Multiple line items can be added or removed.
4. **Live Total Calculation** — The estimated purchase total updates in real time as items are selected and quantities adjusted.
5. **Save Draft Order** — Submits the order as a `DRAFT`, which then appears in the order list.

#### Order Detail / Inspection View

Clicking into an order shows:
- **Order Lifecycle Journey** — A visual stepper showing progress through the 5 primary states (Draft → Pending Approval → Approved → Shipped → Delivered), with the current state highlighted.
- **Order Metadata** — Order number, supplier, warehouse, originator username, and net total.
- **Lifecycle Controller** — Role-aware action buttons for advancing the order state (see FSM section below).
- **Itemized Allocation Listing** — A table of all products in the order with quantities, unit prices, and line subtotals.

---

### 4. Forensic Audit Logs *(Admin Only)*

**Route:** `/audit-logs`
**Access:** `ROLE_ADMIN` only

A read-only, immutable trail of every significant mutation that has occurred in the system.

#### Filtering

- **Search** by Entity ID or System Actor (user) ID.
- **Entity Type filter:** All Entities, Products, or Orders.
- **Action Type filter:** All Actions, Stock Overrides, Create Order, or Update Status.

#### Audit Log Table

| Column | Description |
|---|---|
| **Timestamp** | Precise date and time the event was recorded |
| **Entity (Class)** | Whether the target was a `Product` or `Order`, plus the entity's UUID |
| **Action / Event** | The event verb badge (e.g., `MANUAL ADJUSTMENT`, `CREATE ORDER`, `UPDATE STATUS`) |
| **System Actor** | The user ID who triggered the action |
| **Details** | Eye icon to open the diff panel |

#### Diff Detail Panel

Clicking any log row opens a side panel showing:
- Full metadata: date, actor, target entity, event verb.
- **Differential State Log** — A table comparing `Old Value` (red) vs. `New Value` (green) for every property that changed in that event. Unchanged properties are shown for context; changed rows are highlighted.

> [!NOTE]
> The audit log is append-only. The backend architecture exposes **zero HTTP `PUT` or `DELETE` routes** on the audit table — no modification or deletion of audit records is possible.

---

## Purchase Order Lifecycle (Finite State Machine)

Orders follow a **strict, deterministic state machine**. Invalid transitions are rejected by the backend with a `400 Bad Request`.

```
[ DRAFT ] ──► [ PENDING_APPROVAL ] ──► [ APPROVED ] ──► [ SHIPPED ] ──► [ DELIVERED ]
                     │                                         │
                     └─────────────────────────────────────────┴──► [ CANCELLED ]
```

### Transition Rules by Role

| From State | Transition | Action Button | Required Role |
|---|---|---|---|
| `DRAFT` | → `PENDING_APPROVAL` | Submit for Approval | Staff or Admin |
| `DRAFT` | → `CANCELLED` | Cancel Order | Staff or Admin |
| `PENDING_APPROVAL` | → `APPROVED` | Approve & Verify | **Admin only** |
| `PENDING_APPROVAL` | → `CANCELLED` | Reject & Cancel | **Admin only** |
| `APPROVED` | → `SHIPPED` | Dispatch / Ship | **Admin only** |
| `APPROVED` | → `CANCELLED` | Cancel Order | **Admin only** |
| `SHIPPED` | → `DELIVERED` | Confirm Delivery | **Admin only** |
| `SHIPPED` | → `CANCELLED` | Cancel Order | **Admin only** |
| `DELIVERED` | — | *(Terminal — locked)* | — |
| `CANCELLED` | — | *(Terminal — locked)* | — |

### Inventory Auto-Replenishment on Delivery

When an Admin confirms delivery (advancing to the `DELIVERED` terminal state):

1. The backend parses all line items in the order.
2. Each product's `stock_quantity` is incremented by the ordered quantity.
3. This entire process runs inside an **atomic database transaction**. If any product update fails, the entire delivery confirmation is rolled back — the order status reverts and no stock is partially updated.

> [!IMPORTANT]
> Staff users who view an order in `PENDING_APPROVAL`, `APPROVED`, or `SHIPPED` state will see an informational message instead of action buttons, explaining that the action requires an admin.

---

## Automated Audit System

Every mutation to the `products` or `orders` tables is **automatically intercepted** by a backend entity listener (JPA `@EntityListeners`). No manual logging calls are needed in service code.

When a tracked entity is modified, the interceptor:

1. Captures the **old state** (pre-mutation snapshot).
2. Captures the **new state** (post-mutation values).
3. Extracts the **acting user identity** from the security context thread.
4. Writes an immutable record to the `audit_logs` table with:
   - Unique transaction ID (`BIGINT`)
   - Target entity type and UUID
   - Action verb (e.g., `ACTION_MANUAL_ADJUSTMENT`, `ACTION_CREATE_ORDER`, `ACTION_UPDATE_ORDER_STATUS`)
   - JSON snapshots of old and new state
   - User ID of the actor
   - Microsecond-precision UTC timestamp

---

## Notifications

**Access:** `ROLE_STAFF` and `ROLE_ADMIN`

The system generates in-app notifications for relevant events (e.g., `LOW_STOCK` alerts when a product drops below its reorder level). Notifications are surfaced in the Header component and can be:
- Marked as **read** individually via `PUT /api/v1/notifications/{id}/read`
- Marked as **all read** via `PUT /api/v1/notifications/read-all`

---

## Security & Non-Functional Behaviour

| Requirement | Implementation |
|---|---|
| Password storage | BCrypt with cost factor ≥ 10 |
| Session mechanism | Stateless JWT (HS256 signature) |
| Token lifetime | 60 minutes hard expiry |
| Invalid input rejection | Bean validation at controller layer; negative quantities, empty SKUs, and oversized strings all return `400 Bad Request` |
| Negative stock prevention | Frontend pre-validates; backend enforces |
| Audit immutability | No `PUT`/`DELETE` routes exist on the audit endpoint |
| Health monitoring | `/health` endpoint verifies active DB connection and returns system status |
| Atomic inventory updates | Delivery confirmation wrapped in `@Transactional` — full rollback on any partial failure |

---

## API Endpoint Reference

| Method | Endpoint | Role Required | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | Public | Authenticate and receive JWT |
| `GET` | `/api/v1/inventory/products` | Staff / Admin | List all products |
| `POST` | `/api/v1/inventory/products` | Admin | Create a new product |
| `POST` | `/api/v1/inventory/products/{id}/adjust` | Admin | Manual stock adjustment with reason code |
| `GET` | `/api/v1/orders` | Staff / Admin | List all purchase orders |
| `POST` | `/api/v1/orders` | Staff / Admin | Create a new purchase order (starts as `DRAFT`) |
| `PUT` | `/api/v1/orders/{id}/status` | Staff / Admin* | Advance order lifecycle state |
| `GET` | `/api/v1/audit-logs` | Admin | Retrieve all immutable audit log entries |
| `GET` | `/api/v1/notifications` | Staff / Admin | Get notifications for current user |
| `PUT` | `/api/v1/notifications/{id}/read` | Staff / Admin | Mark a notification as read |
| `PUT` | `/api/v1/notifications/read-all` | Staff / Admin | Mark all notifications as read |
| `GET` | `/api/v1/analytics/dashboard` | Admin | Retrieve dashboard KPI metrics |

*Staff may only transition to `PENDING_APPROVAL`. Transitions to `APPROVED`, `SHIPPED`, `DELIVERED`, and `CANCELLED` (from non-Draft states) require `ROLE_ADMIN`.
