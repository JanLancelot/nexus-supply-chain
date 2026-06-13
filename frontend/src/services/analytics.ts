import api from './api';
import { type DashboardMetrics } from '../types';

export const getDashboardMetrics = async (): Promise<DashboardMetrics> => {
  const response = await api.get('/analytics/dashboard');
  return response.data;
};
