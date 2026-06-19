-- Clean up existing transaction logs to start seeding cleanly
TRUNCATE TABLE audit_logs CASCADE;
TRUNCATE TABLE notifications CASCADE;
TRUNCATE TABLE order_items CASCADE;
TRUNCATE TABLE orders CASCADE;
TRUNCATE TABLE supplier_products CASCADE;
DELETE FROM products WHERE sku LIKE 'SKU-GEN-%';

-- Create dynamic lookup temporary tables to handle dynamically generated base UUIDs
CREATE TEMP TABLE lookup_users AS SELECT id, email FROM users;
CREATE TEMP TABLE lookup_categories AS SELECT id, name FROM product_categories;
CREATE TEMP TABLE lookup_warehouses AS SELECT id, name FROM warehouses;
CREATE TEMP TABLE lookup_suppliers AS SELECT id, name FROM suppliers;

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
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_users WHERE email = 'admin@pg.com' LIMIT 1)
         ELSE (SELECT id FROM lookup_users WHERE email = 'staff@pg.com' LIMIT 1) END,
    NOW() - (i % 365) * INTERVAL '1 day',
    NOW() - (i % 365) * INTERVAL '1 day'
FROM generate_series(1, 500000) i;

-- 3. Seed 1,000,000 Order Items (2 items per generated order)
CREATE TEMP TABLE temp_prod AS 
SELECT row_number() OVER() as rn, id, unit_price FROM products;

CREATE INDEX idx_temp_prod_rn ON temp_prod(rn);

INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, subtotal)
SELECT 
    gen_random_uuid(),
    o.id,
    p.id,
    qty,
    p.unit_price,
    qty * p.unit_price
FROM (
    SELECT id, row_number() OVER() as rn
    FROM orders
    WHERE order_number LIKE 'ORD-GEN-%'
) o
CROSS JOIN LATERAL (
    SELECT 1 as item_num, (o.rn % 100000) + 1 as prod_rn, (5 + (o.rn % 20))::integer as qty
    UNION ALL
    SELECT 2 as item_num, ((o.rn + 50000) % 100000) + 1 as prod_rn, (1 + (o.rn % 10))::integer as qty
) item_spec
JOIN temp_prod p ON p.rn = item_spec.prod_rn;

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
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_users WHERE email = 'admin@pg.com' LIMIT 1)
         ELSE (SELECT id FROM lookup_users WHERE email = 'staff@pg.com' LIMIT 1) END,
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
    CASE WHEN (i % 2) = 0 THEN (SELECT id FROM lookup_users WHERE email = 'admin@pg.com' LIMIT 1)
         ELSE (SELECT id FROM lookup_users WHERE email = 'staff@pg.com' LIMIT 1) END,
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

