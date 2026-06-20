CREATE TABLE IF NOT EXISTS supplier_products (
    supplier_id UUID NOT NULL,
    product_id UUID NOT NULL,
    supply_price DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (supplier_id, product_id)
);
