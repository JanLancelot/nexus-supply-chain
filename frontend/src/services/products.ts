import api from './api';
import { type Product, type PagedResponse } from '../types';

export interface ProductFilters {
  search?: string;
  warehouseId?: string;
  active?: boolean;
}

export const getProducts = async (page = 0, size = 50, filters: ProductFilters = {}): Promise<PagedResponse<Product>> => {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.search?.trim()) params.set('search', filters.search.trim());
  if (filters.warehouseId) params.set('warehouseId', filters.warehouseId);
  if (filters.active !== undefined) params.set('active', String(filters.active));
  const response = await api.get(`/inventory/products?${params}`);
  return response.data;
};

export interface CreateProductData {
  sku: string;
  name: string;
  reorderLevel: number;
  categoryId?: string;
  warehouseId: string;
  unitPrice: number;
}

export const createProduct = async (data: CreateProductData): Promise<Product> => {
  const response = await api.post('/inventory/products', data);
  return response.data;
};

export interface AdjustStockData {
  quantityAdjustment: number;
  reasonCode: 'CYCLIC_COUNT_DISCREPANCY' | 'DAMAGED_GOODS_SCRAP' | 'SUPPLIER_SHORTAGE';
}

export const adjustProductStock = async (id: string, data: AdjustStockData): Promise<Product> => {
  const response = await api.post(`/inventory/products/${id}/adjust`, data);
  return response.data;
};

// Expose category and warehouse retrievals
export interface Category {
  id: string;
  name: string;
  description?: string;
}

export interface Warehouse {
  id: string;
  name: string;
  location?: string;
}

export const getCategories = async (): Promise<Category[]> => {
  const response = await api.get('/categories');
  return response.data;
};

export const getWarehouses = async (): Promise<Warehouse[]> => {
  const response = await api.get('/warehouses');
  return response.data;
};

export const createCategory = async (data: { name: string; description?: string }): Promise<Category> => {
  const response = await api.post('/categories', data);
  return response.data;
};

export const createWarehouse = async (data: { name: string; location?: string }): Promise<Warehouse> => {
  const response = await api.post('/warehouses', data);
  return response.data;
};

export const getProduct = async (id: string): Promise<Product> => {
  const response = await api.get(`/inventory/products/${id}`);
  return response.data;
};
