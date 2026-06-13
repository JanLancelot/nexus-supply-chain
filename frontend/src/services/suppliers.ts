import api from './api';

export interface Supplier {
  id: string;
  name: string;
  contactPerson?: string;
  email?: string;
  phone?: string;
  address?: string;
  active: boolean;
}

export const getSuppliers = async (): Promise<Supplier[]> => {
  const response = await api.get('/suppliers');
  return response.data;
};
