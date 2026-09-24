"""Execute the fixture's portable order-item SQL against a tiny local database."""

from pathlib import Path
import sqlite3
import unittest
import uuid


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


class ScaleFixtureTests(unittest.TestCase):
    def test_generated_items_keep_order_warehouses_and_use_distinct_active_products(self):
        fixture = (REPOSITORY_ROOT / "docker" / "scale_data.sql").read_text(encoding="utf-8")
        item_sql = fixture.split("-- 3. Seed 1,000,000 Order Items", 1)[1].split("-- Bulk update order totals", 1)[0]
        item_sql = item_sql[item_sql.index("CREATE TEMP TABLE temp_prod"):]
        with sqlite3.connect(":memory:") as database:
            database.create_function("gen_random_uuid", 0, lambda: str(uuid.uuid4()))
            database.executescript("""
                CREATE TABLE products (id TEXT PRIMARY KEY, warehouse_id TEXT, unit_price NUMERIC, is_active BOOLEAN);
                CREATE TABLE orders (id TEXT PRIMARY KEY, warehouse_id TEXT, order_number TEXT);
                CREATE TABLE order_items (id TEXT, order_id TEXT, product_id TEXT, quantity INTEGER, unit_price NUMERIC, subtotal NUMERIC);
            """)
            # Interleave names and include inactive/unassigned products so a
            # global row-number lookup would mix locations or select bad stock.
            database.executemany("INSERT INTO products VALUES (?, ?, ?, ?)", [
                ("a-west", "west", 1, True), ("b-east", "east", 2, True),
                ("c-west", "west", 3, True), ("d-east", "east", 4, True),
                ("e-west", "west", 5, True), ("f-east", "east", 6, True),
                ("inactive", "west", 100, False), ("unassigned", None, 100, True),
            ])
            database.executemany("INSERT INTO orders VALUES (?, ?, ?)", [
                (f"order-{index:03d}", "east" if index % 2 else "west", f"ORD-GEN-{index}")
                for index in range(1, 25)
            ] + [("unrelated", "east", "ORD-EXISTING")])
            database.executescript(item_sql)
            self.assertEqual(database.execute("SELECT COUNT(*) FROM order_items").fetchone()[0], 48)
            self.assertEqual(database.execute("""
                SELECT COUNT(*) FROM order_items oi
                JOIN products p ON p.id = oi.product_id
                JOIN orders o ON o.id = oi.order_id
                WHERE p.warehouse_id <> o.warehouse_id OR p.is_active <> TRUE
                      OR p.warehouse_id IS NULL OR oi.quantity <= 0
                      OR oi.subtotal <> oi.unit_price * oi.quantity
            """).fetchone()[0], 0)
            self.assertEqual(database.execute("""
                SELECT COUNT(*) FROM (
                    SELECT order_id FROM order_items GROUP BY order_id
                    HAVING COUNT(*) <> 2 OR COUNT(DISTINCT product_id) <> 2
                )
            """).fetchone()[0], 0)
            self.assertEqual(database.execute("SELECT COUNT(*) FROM order_items WHERE order_id = 'unrelated'").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
