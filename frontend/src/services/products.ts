import api from './api';
import { type Product } from '../types';

export const getProducts = async (): Promise<Product[]> => {
  const response = await api.get('/inventory/products');
  return response.data;
};

export interface CreateProductData {
  sku: string;
  name: string;
  reorderLevel: number;
  categoryId?: string;
  warehouseId?: string;
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
