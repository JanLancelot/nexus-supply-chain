import api from './api';
import { type AuditLog } from '../types';

export const getAuditLogs = async (page: number = 0, size: number = 50): Promise<AuditLog[]> => {
  const response = await api.get(`/audit-logs?page=${page}&size=${size}`);
  return response.data;
};
