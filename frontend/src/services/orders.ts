import api from './api';
import { type Order } from '../types';

export const getOrders = async (): Promise<Order[]> => {
  const response = await api.get('/orders');
  return response.data;
};

export const getOrderById = async (id: string): Promise<Order> => {
  const response = await api.get(`/orders/${id}`);
  return response.data;
};

export interface CreateOrderItemData {
  productId: string;
  quantity: number;
}

export interface CreateOrderData {
  supplierId: string;
  warehouseId: string;
  items: CreateOrderItemData[];
}

export const createOrder = async (data: CreateOrderData): Promise<Order> => {
  const response = await api.post('/orders', data);
  return response.data;
};

export interface UpdateOrderStatusData {
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';
}

export const updateOrderStatus = async (id: string, data: UpdateOrderStatusData): Promise<Order> => {
  const response = await api.put(`/orders/${id}/status`, data);
  return response.data;
};
