import api from './api';

export interface Supplier {
  id: string;
  name: string;
  contactPerson?: string;
  email?: string;
  phone?: string;
  address?: string;
  active: boolean;
  leadTimeDays: number;
  productIds: string[];
}

export const getSuppliers = async (): Promise<Supplier[]> => {
  const response = await api.get('/suppliers');
  return response.data;
};

export interface CreateSupplierData {
  name: string;
  contactPerson?: string;
  email?: string;
  phone?: string;
  address?: string;
  leadTimeDays: number;
  isActive: boolean;
  productIds?: string[];
}

export const createSupplier = async (data: CreateSupplierData): Promise<Supplier> => {
  const response = await api.post('/suppliers', data);
  return response.data;
};

export const setSupplierProducts = async (id: string, productIds: string[]): Promise<void> => {
  await api.put(`/suppliers/${id}/products`, { productIds });
};
