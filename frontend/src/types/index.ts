export interface User {
  id: string;
  fullName: string;
  email: string;
  role: string;
  status: string;
}

export interface Product {
  id: string;
  sku: string;
  name: string;
  description?: string;
  unitPrice: number;
  stockQuantity: number;
  reorderLevel: number;
  lowStockIndicator: boolean;
  categoryId?: string;
  categoryName?: string;
  warehouseId?: string;
  warehouseName?: string;
}

export interface OrderItem {
  productId: string;
  productName?: string;
  productSku?: string;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

export interface Order {
  id: string;
  orderNumber: string;
  supplierId: string;
  supplierName: string;
  warehouseId: string;
  warehouseName: string;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';
  totalAmount: number;
  createdBy: string;
  createdAt?: string;
  items: OrderItem[];
}

export interface AuditLog {
  id: string;
  userId: string;
  entityType: string;
  entityId: string;
  action: string;
  oldValue: string; // JSON string
  newValue: string; // JSON string
  createdAt: string;
}

export interface Notification {
  id: string;
  userId: string;
  type: string;
  message: string;
  isRead: boolean;
  createdAt: string;
}

export interface TopProduct {
  name: string;
  totalQuantityOrdered: number;
}

export interface DashboardMetrics {
  totalRevenue: number;
  orderStatusCounts: Record<string, number>;
  lowStockCount: number;
  totalInventoryValue: number;
  warehouseStockCounts: Record<string, number>;
  topProducts: TopProduct[];
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  pageNumber: number;
  pageSize: number;
  hasNext: boolean;
}

