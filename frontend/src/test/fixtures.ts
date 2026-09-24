import type { DashboardMetrics, Order, PagedResponse, Product, User } from '../types';

export const products: Product[] = [
  { id: 'product-1', sku: 'SOAP-01', name: 'Hand Soap', unitPrice: 12.5, stockQuantity: 30,
    reorderLevel: 10, lowStockIndicator: false, isActive: true, categoryId: 'care', categoryName: 'Personal Care',
    warehouseId: 'warehouse-1', warehouseName: 'Central Warehouse' },
  { id: 'product-2', sku: 'PAPER-02', name: 'Paper Towels', unitPrice: 7.25, stockQuantity: 3,
    reorderLevel: 5, lowStockIndicator: true, isActive: true, categoryId: 'home', categoryName: 'Household',
    warehouseId: 'warehouse-1', warehouseName: 'Central Warehouse' },
];

export const categories = [{ id: 'care', name: 'Personal Care' }, { id: 'home', name: 'Household' }];
export const warehouses = [{ id: 'warehouse-1', name: 'Central Warehouse' }];
export const suppliers = [
  { id: 'supplier-1', name: 'North Supply', active: true, productIds: [], leadTimeDays: 3 },
  { id: 'supplier-2', name: 'Retired Supplier', active: false, productIds: [], leadTimeDays: 3 },
];

export const users: User[] = [
  { id: 'user-2', fullName: 'Zoe Staff', email: 'zoe@example.com', role: 'ROLE_STAFF', status: 'ACTIVE' },
  { id: 'user-1', fullName: 'Ari Admin', email: 'ari@example.com', role: 'ROLE_ADMIN', status: 'ACTIVE' },
];

export function purchaseOrder(overrides: Partial<Order> = {}): Order {
  return {
    id: 'order-1', orderNumber: 'PO-1001', supplierId: 'supplier-1', supplierName: 'North Supply',
    warehouseId: 'warehouse-1', warehouseName: 'Central Warehouse', status: 'DRAFT',
    totalAmount: 25, createdBy: 'operator@example.com', createdAt: '2026-01-02T10:00:00Z',
    items: [{ productId: 'product-1', productName: 'Hand Soap', productSku: 'SOAP-01',
      quantity: 2, unitPrice: 12.5, subtotal: 25 }],
    ...overrides,
  };
}

export function pageOf<T>(content: T[], pageNumber = 0, hasNext = false): PagedResponse<T> {
  return { content, pageNumber, hasNext, pageSize: 50, totalElements: content.length,
    totalPages: hasNext ? pageNumber + 2 : pageNumber + 1 };
}

export const catalogResponses = {
  'GET /inventory/products?page=0&size=50': { data: pageOf(products) },
  'GET /categories': { data: categories },
  'GET /warehouses': { data: warehouses },
};

export const orderResponses = {
  'GET /orders?page=0&size=50': { data: pageOf([purchaseOrder()]) },
  'GET /suppliers': { data: suppliers },
  'GET /warehouses': { data: warehouses },
  'GET /inventory/products?page=0&size=50&warehouseId=warehouse-1&active=true': { data: pageOf(products) },
};

export const metrics: DashboardMetrics = {
  totalRevenue: 1250.5, totalInventoryValue: 7425, lowStockCount: 2,
  orderStatusCounts: { DRAFT: 1, DELIVERED: 3 },
  warehouseStockCounts: [{ warehouseId: 'warehouse-1', warehouseName: 'Central Warehouse', totalStock: 400 }, { warehouseId: 'warehouse-2', warehouseName: 'East Warehouse', totalStock: 150 }],
  topProducts: [{ name: 'Hand Soap', totalQuantityOrdered: 75 }, { name: 'Paper Towels', totalQuantityOrdered: 25 }],
};
