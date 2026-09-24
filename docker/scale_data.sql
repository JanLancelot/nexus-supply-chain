-- Destructive fixture reset for a disposable benchmark database only.
-- Invoke psql with -v ALLOW_DESTRUCTIVE_SEED=true to opt in.
\set ON_ERROR_STOP on
\if :{?ALLOW_DESTRUCTIVE_SEED}
\else
  \echo 'Refusing fixture reset: ALLOW_DESTRUCTIVE_SEED must be true.'
  DO $$ BEGIN RAISE EXCEPTION 'Fixture reset not authorized'; END $$;
\endif
\if :ALLOW_DESTRUCTIVE_SEED
\else
  \echo 'Refusing fixture reset: ALLOW_DESTRUCTIVE_SEED must be true.'
  DO $$ BEGIN RAISE EXCEPTION 'Fixture reset not authorized'; END $$;
\endif
BEGIN;
-- Validate the optional demo fixtures before acquiring destructive table locks.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
                   WHERE u.status = 'ACTIVE' AND r.name = 'ROLE_ADMIN')
       OR NOT EXISTS (SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
                      WHERE u.status = 'ACTIVE' AND r.name = 'ROLE_STAFF') THEN
        RAISE EXCEPTION 'Benchmark fixtures require active administrator and staff accounts. Enable APP_SEED_DEMO_DATA and configure bootstrap passwords in a disposable environment first.';
    END IF;
    IF (SELECT count(DISTINCT name) FROM product_categories
        WHERE name IN ('Electronics', 'Industrial', 'Office Supplies')) <> 3 THEN
        RAISE EXCEPTION 'Benchmark fixtures require the three demo product categories. Load demo data before resetting fixtures.';
    END IF;
    IF (SELECT count(DISTINCT name) FROM warehouses
        WHERE name IN ('Main Distribution Center', 'Regional Fulfillment Hub')) <> 2 THEN
        RAISE EXCEPTION 'Benchmark fixtures require the two demo warehouses. Load demo data before resetting fixtures.';
    END IF;
    IF (SELECT count(DISTINCT name) FROM suppliers WHERE is_active = TRUE
        AND name IN ('Apex Logistics & Supplies', 'Global Tech Parts')) <> 2 THEN
        RAISE EXCEPTION 'Benchmark fixtures require the two active demo suppliers. Load demo data before resetting fixtures.';
    END IF;
END $$;

-- Clean up existing transaction logs to start seeding cleanly
TRUNCATE TABLE audit_logs CASCADE;
TRUNCATE TABLE notifications CASCADE;
TRUNCATE TABLE order_items CASCADE;
TRUNCATE TABLE orders CASCADE;
TRUNCATE TABLE supplier_products CASCADE;
DELETE FROM products WHERE sku LIKE 'SKU-GEN-%';

-- Create dynamic lookup temporary tables to handle dynamically generated base UUIDs
CREATE TEMP TABLE lookup_users AS SELECT u.id, r.name AS role FROM users u JOIN roles r ON r.id = u.role_id WHERE u.status = 'ACTIVE';
CREATE TEMP TABLE lookup_categories AS SELECT id, name FROM product_categories;
CREATE TEMP TABLE lookup_warehouses AS SELECT id, name FROM warehouses;
CREATE TEMP TABLE lookup_suppliers AS SELECT id, name FROM suppliers WHERE is_active = TRUE;

-- 1. Seed 100,000 Products
INSERT INTO products (id, sku, name, description, category_id, unit_price, stock_quantity, reorder_level, warehouse_id, is_active, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'SKU-GEN-' || i,
    'Generated Product ' || i,
    'Description for generated product ' || i,
    CASE WHEN (i % 3) = 0 THEN (SELECT id FROM lookup_categories WHERE name = 'Electronics' LIMIT 1)
         WHEN (i % 3) = 1 THEN (SELECT id FROM lookup_categories WHERE name = 'Industrial' LIMIT 1)
         ELSE (SELECT id FROM lookup_categories WHERE name = 'Office Supplies' LIMIT 1) END,
    (10.0 + (i % 1000)::numeric * 0.5)::numeric(12,2),
    10 + (i % 500),
    5 + (i % 20),
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_warehouses WHERE name = 'Main Distribution Center' LIMIT 1)
         ELSE (SELECT id FROM lookup_warehouses WHERE name = 'Regional Fulfillment Hub' LIMIT 1) END,
    TRUE,
    NOW() - (i % 365) * INTERVAL '1 day',
    NOW() - (i % 365) * INTERVAL '1 day'
FROM generate_series(1, 100000) i;

-- 2. Seed 500,000 Orders
INSERT INTO orders (id, order_number, supplier_id, warehouse_id, status, total_amount, created_by, created_at, updated_at)
SELECT 
    gen_random_uuid(),
    'ORD-GEN-' || i,
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_suppliers WHERE name = 'Apex Logistics & Supplies' LIMIT 1)
         ELSE (SELECT id FROM lookup_suppliers WHERE name = 'Global Tech Parts' LIMIT 1) END,
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_warehouses WHERE name = 'Main Distribution Center' LIMIT 1)
         ELSE (SELECT id FROM lookup_warehouses WHERE name = 'Regional Fulfillment Hub' LIMIT 1) END,
    CASE WHEN (i % 4) = 0 THEN 'DRAFT'
         WHEN (i % 4) = 1 THEN 'PENDING_APPROVAL'
         WHEN (i % 4) = 2 THEN 'APPROVED'
         ELSE 'DELIVERED' END,
    0.00,
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_users WHERE role = 'ROLE_ADMIN' LIMIT 1)
         ELSE (SELECT id FROM lookup_users WHERE role = 'ROLE_STAFF' LIMIT 1) END,
    NOW() - (i % 365) * INTERVAL '1 day',
    NOW() - (i % 365) * INTERVAL '1 day'
FROM generate_series(1, 500000) i;

-- 3. Seed 1,000,000 Order Items (2 items per generated order)
-- Each product has one warehouse balance; every line must match its order destination.
CREATE TEMP TABLE temp_prod AS
SELECT row_number() OVER (PARTITION BY warehouse_id ORDER BY id) AS rn,
       id, unit_price, warehouse_id
FROM products
WHERE is_active = TRUE AND warehouse_id IS NOT NULL;

CREATE INDEX idx_temp_prod_warehouse_rn ON temp_prod(warehouse_id, rn);
CREATE TEMP TABLE temp_prod_counts AS
SELECT warehouse_id, COUNT(*) AS product_count
FROM temp_prod
GROUP BY warehouse_id;

INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, subtotal)
SELECT
    gen_random_uuid(),
    o.id,
    p.id,
    CASE WHEN item_spec.item_num = 1 THEN 5 + (o.rn % 20) ELSE 1 + (o.rn % 10) END,
    p.unit_price,
    (CASE WHEN item_spec.item_num = 1 THEN 5 + (o.rn % 20) ELSE 1 + (o.rn % 10) END) * p.unit_price
FROM (
    SELECT id, warehouse_id,
           row_number() OVER (PARTITION BY warehouse_id ORDER BY id) AS rn
    FROM orders
    WHERE order_number LIKE 'ORD-GEN-%'
) o
JOIN temp_prod_counts counts ON counts.warehouse_id = o.warehouse_id AND counts.product_count >= 2
CROSS JOIN (SELECT 1 AS item_num UNION ALL SELECT 2 AS item_num) item_spec
JOIN temp_prod p ON p.warehouse_id = o.warehouse_id
    AND p.rn = ((o.rn + CASE WHEN item_spec.item_num = 1 THEN 0 ELSE counts.product_count / 2 END)
                % counts.product_count) + 1;

-- Bulk update order totals based on order items
UPDATE orders o
SET total_amount = sub.sum_subtotal
FROM (
    SELECT order_id, SUM(subtotal) as sum_subtotal
    FROM order_items
    GROUP BY order_id
) sub
WHERE o.id = sub.order_id AND o.order_number LIKE 'ORD-GEN-%';

-- 4. Seed 1,000,000 Audit Logs
INSERT INTO audit_logs (id, user_id, entity_type, entity_id, action, old_value, new_value, created_at)
SELECT 
    gen_random_uuid(),
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_users WHERE role = 'ROLE_ADMIN' LIMIT 1)
         ELSE (SELECT id FROM lookup_users WHERE role = 'ROLE_STAFF' LIMIT 1) END,
    'Order',
    o.id,
    'ACTION_UPDATE_ORDER_STATUS',
    '{"status": "PENDING_APPROVAL"}'::jsonb,
    '{"status": "APPROVED"}'::jsonb,
    NOW() - (i % 365) * INTERVAL '1 day'
FROM generate_series(1, 1000000) i
JOIN (
    SELECT id, row_number() OVER() as rn
    FROM orders
) o ON o.rn = ((i % 500000) + 1);

-- 5. Seed 500,000 Notifications
INSERT INTO notifications (id, user_id, type, message, is_read, created_at)
SELECT 
    gen_random_uuid(),
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_users WHERE role = 'ROLE_ADMIN' LIMIT 1)
         ELSE (SELECT id FROM lookup_users WHERE role = 'ROLE_STAFF' LIMIT 1) END,
    'ORDER_STATUS_UPDATE',
    'Order ORD-GEN-' || (i % 500000 + 1) || ' has been approved.',
    (i % 3) = 0,
    NOW() - (i % 365) * INTERVAL '1 day'
FROM generate_series(1, 500000) i;

-- 6. Seed supplier_products
INSERT INTO supplier_products (supplier_id, product_id, supply_price)
SELECT 
    CASE WHEN (row_number() OVER() % 2) = 0 THEN (SELECT id FROM lookup_suppliers WHERE name = 'Apex Logistics & Supplies' LIMIT 1)
         ELSE (SELECT id FROM lookup_suppliers WHERE name = 'Global Tech Parts' LIMIT 1) END,
    id,
    (unit_price * 0.8)::numeric(12,2)
FROM products;

-- Refresh query planner statistics for the newly generated records
ANALYZE;


COMMIT;
