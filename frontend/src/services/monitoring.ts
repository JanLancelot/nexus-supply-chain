import api from './api';
import type { MonitoringConfiguration } from '../types';

export async function getMonitoringConfiguration(): Promise<MonitoringConfiguration> {
  const { data } = await api.get<MonitoringConfiguration>('/monitoring');
  if (data.enabled === false) return { enabled: false, grafanaUrl: null };

  // Treat the configured external destination as untrusted at the HTTP boundary.
  if (data.enabled !== true || typeof data.grafanaUrl !== 'string') throw new Error('Invalid monitoring configuration');
  const url = new URL(data.grafanaUrl);
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Invalid monitoring URL');
  }
  return { enabled: true, grafanaUrl: url.href };
}
