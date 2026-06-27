import api from './api';
import { type User } from '../types';

export interface UserCreateData {
  fullName: string;
  email: string;
  password?: string;
  roleName: string;
}

export const getUsers = async (): Promise<User[]> => {
  const response = await api.get('/users');
  return response.data;
};

export const createUser = async (userData: UserCreateData): Promise<User> => {
  const response = await api.post('/users', userData);
  return response.data;
};
