import { useEffect, useState } from 'react';
import { Activity, ExternalLink, Package, RefreshCw, ShieldCheck } from 'lucide-react';
import { getMonitoringConfiguration } from '../services/monitoring';
import type { MonitoringConfiguration } from '../types';

type MonitoringState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'loaded'; configuration: MonitoringConfiguration };

export default function Monitoring() {
  const [state, setState] = useState<MonitoringState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    void getMonitoringConfiguration().then(configuration => {
      if (active) setState({ status: 'loaded', configuration });
    }).catch(() => {
      if (active) setState({ status: 'error' });
    });
    return () => { active = false; };
  }, [attempt]);

  const retry = () => {
    setState({ status: 'loading' });
    setAttempt(value => value + 1);
  };

  return (
    <div className="max-w-5xl space-y-6">
      <section className="glass-panel rounded-2xl p-5 md:p-8">
        <div className="flex items-center gap-3 text-indigo-400 mb-4">
          <Activity className="h-6 w-6" aria-hidden="true" />
          <span className="text-xs font-semibold uppercase tracking-wider">Grafana dashboards</span>
        </div>
        <h2 className="text-xl md:text-2xl font-bold text-white">Understand your operations over time</h2>
        <p className="mt-3 text-sm leading-6 text-gray-400 max-w-2xl">
          Explore application performance and supply-chain trends in Grafana to spot issues and plan your next steps.
        </p>

        {state.status === 'loading' && (
          <p role="status" className="flex items-center gap-2 mt-6 text-sm text-gray-400">
            <RefreshCw className="h-4 w-4 animate-spin" aria-hidden="true" />
            Loading monitoring settings…
          </p>
        )}

        {state.status === 'error' && (
          <div role="alert" className="mt-6 rounded-xl border border-red-500/25 bg-red-950/30 p-4">
            <p className="text-sm text-red-300">Unable to load monitoring. Please try again.</p>
            <button type="button" onClick={retry} className="mt-3 inline-flex items-center gap-2 rounded-lg border border-gray-700 px-4 py-2 text-sm text-white hover:bg-gray-800 cursor-pointer">
              <RefreshCw className="h-4 w-4" aria-hidden="true" />
              Try again
            </button>
          </div>
        )}

        {state.status === 'loaded' && (state.configuration.enabled ? (
          <div className="mt-6 space-y-4">
            <a href={state.configuration.grafanaUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center justify-center gap-2 rounded-lg border border-indigo-500/40 bg-indigo-600 px-5 py-3 text-sm font-semibold text-white hover:bg-indigo-500 transition-colors">
              Open Grafana
              <ExternalLink className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only"> (opens in a new tab)</span>
            </a>
            <p className="flex items-start gap-2 text-xs leading-5 text-gray-400">
              <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-indigo-400" aria-hidden="true" />
              Grafana opens in a new tab and requires a separate operator login. Contact your administrator if you need access.
            </p>
          </div>
        ) : (
          <div className="mt-6 rounded-xl border border-gray-700 bg-gray-900/40 p-5">
            <h3 className="text-sm font-semibold text-white">Monitoring is not configured yet</h3>
            <p className="mt-2 text-sm leading-6 text-gray-400">Ask the team managing this environment to connect Grafana. Once connected, you can open the dashboards here.</p>
          </div>
        ))}
      </section>

      <div className="grid gap-4 md:grid-cols-2">
        <section className="glass-panel rounded-2xl p-5 md:p-6">
          <Activity className="h-5 w-5 text-indigo-400 mb-3" aria-hidden="true" />
          <h3 className="text-sm font-semibold text-white">Application health</h3>
          <p className="mt-2 text-sm leading-6 text-gray-400">Track requests, errors, response times, and runtime resource usage to investigate slowdowns and failures.</p>
        </section>
        <section className="glass-panel rounded-2xl p-5 md:p-6">
          <Package className="h-5 w-5 text-emerald-400 mb-3" aria-hidden="true" />
          <h3 className="text-sm font-semibold text-white">Supply-chain trends</h3>
          <p className="mt-2 text-sm leading-6 text-gray-400">Follow inventory levels, low-stock products, and order status over time to understand changes in your operation.</p>
        </section>
      </div>
    </div>
  );
}
