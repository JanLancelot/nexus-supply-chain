# Requirements and implemented scope

This repository demonstrates inventory and purchase-order workflows. It does not establish compliance with an external organization's standards.

## Role permissions

| Operation | Staff | Administrator |
|---|---|---|
| Read products, categories, warehouses, suppliers, orders | Yes | Yes |
| Create draft orders and submit them for approval | Yes | Yes |
| Approve, ship, or deliver orders | No | Yes |
| Cancel a draft/pending order | Yes | Yes |
| Cancel an approved/shipped order | No | Yes |
| Create products or manually adjust stock | No | Yes |
| Change categories and warehouses | No | Yes |
| Read analytics/audit logs or create users | No | Yes |
| Read/update personal notifications | Own records | Own records |

There is no product edit/delete endpoint or supplier-management API. Do not infer full CRUD support from the presence of a database entity.

## Inventory and orders

Products have unique SKUs, a unit price, stock quantity, reorder threshold, category, and warehouse. Manual adjustments require an allowed reason code and cannot make stock negative or overflow its integer representation.

Orders begin in `DRAFT`. Allowed forward transitions are `PENDING_APPROVAL`, `APPROVED`, `SHIPPED`, then `DELIVERED`. A nonterminal order may be cancelled according to role permissions. Delivered and cancelled orders are terminal. Delivery updates stock and order status together.

Low stock means `stockQuantity < reorderLevel`. Event handlers can create a draft replenishment order for an active product with a supplier and warehouse. Operators still approve the draft.

## Audit and notifications

Audit records include an entity identifier, action, actor, before/after values, and creation time. The API exposes an administrator read endpoint and no audit update/delete endpoints. Direct database administrators can still modify the rows.

Notifications are stored per user. List responses include the latest notifications and total/unread counts. Counts can exceed the size of the returned list.

## Limits

The code does not implement tenant isolation, payments, supplier integrations, GPS tracking, a refresh-token flow, database-level audit immutability, or guaranteed event delivery. Products can reference multiple warehouse records, but each product has one stock balance and one warehouse; stock transfers are not implemented.

Performance and test coverage must be measured for the deployed revision and workload. They are not requirements satisfied merely by having caching or test files.
