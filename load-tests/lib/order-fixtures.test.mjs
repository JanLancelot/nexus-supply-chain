import assert from 'node:assert/strict';
import test from 'node:test';
import { selectOrderReferences } from './order-fixtures.mjs';

const first = (items) => items[0] ?? null;
const last = (items) => items.at(-1) ?? null;
const suppliers = [{ id: 'inactive-supplier', active: false }, { id: 'supplier', active: true }];
const warehouses = [{ id: 'east' }, { id: 'west' }];
const products = [
  { id: 'inactive', warehouseId: 'east', isActive: false },
  { id: 'unassigned', isActive: true },
  { id: 'missing-warehouse', warehouseId: 'deleted', isActive: true },
  { id: 'west-product', warehouseId: 'west', isActive: true },
  { id: 'east-product', warehouseId: 'east', isActive: true },
];

test('the chosen product supplies its own destination and excludes inactive reference data', () => {
  assert.deepEqual(selectOrderReferences(suppliers, warehouses, products, first), {
    supplierId: 'supplier', warehouseId: 'west', productId: 'west-product',
  });
  assert.deepEqual(selectOrderReferences(suppliers, warehouses, products, last), {
    supplierId: 'supplier', warehouseId: 'east', productId: 'east-product',
  });
});

test('no order is generated when no active supplier or compatible product exists', () => {
  assert.equal(selectOrderReferences([], warehouses, products, first), null);
  assert.equal(selectOrderReferences([{ id: 'off', active: false }], warehouses, products, first), null);
  assert.equal(selectOrderReferences(suppliers, [], products, first), null);
  assert.equal(selectOrderReferences(suppliers, warehouses, products.slice(0, 3), first), null);
  assert.equal(selectOrderReferences(null, null, null, first), null);
});

test('an unrecognized activity flag is not treated as an active record', () => {
  assert.equal(selectOrderReferences([{ id: 'supplier' }], warehouses, products, first), null);
  assert.equal(selectOrderReferences(suppliers, warehouses, [{ id: 'product', warehouseId: 'east' }], first), null);
});
