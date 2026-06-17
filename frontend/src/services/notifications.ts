import api from './api';
import { type Notification } from '../types';

export interface NotificationListResponse {
  notifications: Notification[];
  totalCount: number;
  unreadCount: number;
}

export const getNotifications = async (): Promise<NotificationListResponse> => {
  const response = await api.get('/notifications');
  return response.data;
};

export const markAsRead = async (id: string): Promise<void> => {
  await api.put(`/notifications/${id}/read`);
};

export const markAllAsRead = async (): Promise<void> => {
  await api.put('/notifications/read-all');
};
