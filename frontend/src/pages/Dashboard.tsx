import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { 
  TrendingUp, 
  DollarSign, 
  AlertTriangle, 
  FileSpreadsheet, 
  RefreshCw,
  Warehouse,
  BarChart3,
  Boxes
} from 'lucide-react';
import { getDashboardMetrics } from '../services/analytics';
import { type DashboardMetrics } from '../types';

const Dashboard: React.FC = () => {
  const { user } = useAuth();
  const [metrics, setMetrics] = useState<DashboardMetrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchMetrics = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getDashboardMetrics();
      setMetrics(data);
    } catch (err: any) {
      console.error(err);
      setError('Failed to fetch dashboard metrics. Confirm the backend container is running.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMetrics();
  }, []);

  if (loading) {
    return (
      <div className="py-20 flex flex-col items-center justify-center">
        <div className="h-8 w-8 border-4 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
        <p className="text-sm text-gray-400">Compiling corporate analytics...</p>
      </div>
    );
  }

  if (error || !metrics) {
    return (
      <div className="p-6 text-center glass-panel rounded-2xl max-w-lg mx-auto mt-12">
        <AlertTriangle className="h-10 w-10 text-red-400 mx-auto mb-3" />
        <h4 className="text-sm font-semibold text-white m-0">Failed to Load Metrics</h4>
        <p className="text-xs text-gray-500 mt-2">{error || 'An unexpected error occurred.'}</p>
        <button
          onClick={fetchMetrics}
          className="mt-4 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-semibold cursor-pointer transition border border-indigo-500/20"
        >
          Retry Load
        </button>
      </div>
    );
  }

  // Helper values
  const totalOrders = Object.values(metrics.orderStatusCounts).reduce((a, b) => a + b, 0);

  // Status color resolver for bars
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'DRAFT': return 'bg-gray-500';
      case 'PENDING_APPROVAL': return 'bg-amber-500';
      case 'APPROVED': return 'bg-blue-500';
      case 'SHIPPED': return 'bg-purple-500';
      case 'DELIVERED': return 'bg-emerald-500';
      case 'CANCELLED': return 'bg-red-500';
      default: return 'bg-indigo-500';
    }
  };

  return (
    <div className="space-y-8">
      {/* Welcome banner */}
      <div className="glass-panel p-6 rounded-2xl flex flex-col md:flex-row items-start md:items-center justify-between gap-4 border border-gray-800/80">
        <div>
          <h3 className="text-xl font-bold text-white tracking-tight m-0">Welcome Back, {user?.fullName.split(' ')[0]}</h3>
          <p className="text-xs text-gray-400 mt-1.5 leading-relaxed">
            Forensic ledger monitoring console. System status is **ONLINE**. No inventory anomalies detected.
          </p>
        </div>
        <button
          onClick={fetchMetrics}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-gray-850 hover:bg-gray-800 text-gray-300 hover:text-white border border-gray-700 hover:border-gray-600 text-xs font-medium rounded-lg transition cursor-pointer"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          <span>Refresh Data</span>
        </button>
      </div>

      {/* KPI Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-6">
        {/* KPI 1: Total Revenue */}
        <div className="glass-panel p-6 rounded-2xl flex items-center justify-between border border-gray-850">
          <div className="space-y-1">
            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Settled Revenue</span>
            <p className="text-2xl font-extrabold text-white font-mono leading-none m-0">
              ${metrics.totalRevenue.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </p>
            <span className="text-[9px] text-emerald-400 font-medium">Delivered Pool</span>
          </div>
          <div className="h-10 w-10 rounded-xl bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center text-emerald-400">
            <DollarSign className="h-5.5 w-5.5" />
          </div>
        </div>

        {/* KPI 2: Total Inventory Value */}
        <div className="glass-panel p-6 rounded-2xl flex items-center justify-between border border-gray-850">
          <div className="space-y-1">
            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Inventory Value</span>
            <p className="text-2xl font-extrabold text-white font-mono leading-none m-0">
              ${metrics.totalInventoryValue.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </p>
            <span className="text-[9px] text-indigo-400 font-medium">Asset Catalog Valuation</span>
          </div>
          <div className="h-10 w-10 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400">
            <Boxes className="h-5.5 w-5.5" />
          </div>
        </div>

        {/* KPI 3: Low Stock Count */}
        <div className="glass-panel p-6 rounded-2xl flex items-center justify-between border border-gray-850">
          <div className="space-y-1">
            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">Safety Discrepancies</span>
            <p className="text-2xl font-extrabold text-white font-mono leading-none m-0">
              {metrics.lowStockCount}
            </p>
            <span className={`text-[9px] font-bold ${metrics.lowStockCount > 0 ? 'text-amber-400 animate-pulse' : 'text-gray-500'}`}>
              {metrics.lowStockCount > 0 ? 'Low Stock Warnings' : 'All Margins Healthy'}
            </span>
          </div>
          <div className={`h-10 w-10 rounded-xl flex items-center justify-center border ${
            metrics.lowStockCount > 0 
              ? 'bg-amber-500/10 border-amber-500/30 text-amber-400 shadow shadow-amber-500/5' 
              : 'bg-gray-500/10 border-gray-500/20 text-gray-400'
          }`}>
            <AlertTriangle className="h-5.5 w-5.5" />
          </div>
        </div>

        {/* KPI 4: Total Orders */}
        <div className="glass-panel p-6 rounded-2xl flex items-center justify-between border border-gray-850">
          <div className="space-y-1">
            <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">System Orders</span>
            <p className="text-2xl font-extrabold text-white font-mono leading-none m-0">
              {totalOrders}
            </p>
            <span className="text-[9px] text-purple-400 font-medium">Recorded Procurement Lifecycles</span>
          </div>
          <div className="h-10 w-10 rounded-xl bg-purple-500/10 border border-purple-500/20 flex items-center justify-center text-purple-400">
            <FileSpreadsheet className="h-5.5 w-5.5" />
          </div>
        </div>
      </div>

      {/* Main Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Chart Card 1: Top Products (Bar chart comparison) */}
        <div className="lg:col-span-2 glass-panel p-6 rounded-2xl border border-gray-850 flex flex-col justify-between">
          <div className="flex items-center justify-between border-b border-gray-850 pb-4 mb-5">
            <h4 className="text-xs font-bold text-white uppercase tracking-wider m-0 flex items-center gap-2">
              <TrendingUp className="h-4.5 w-4.5 text-indigo-400" />
              <span>Top Ordered Product Volumes</span>
            </h4>
            <span className="text-[9px] text-gray-500 uppercase font-mono">By units ordered</span>
          </div>

          <div className="space-y-4">
            {metrics.topProducts.length === 0 ? (
              <div className="py-12 text-center text-xs text-gray-500">No ordered products registered yet.</div>
            ) : (
              metrics.topProducts.map((p, idx) => {
                const maxQty = Math.max(...metrics.topProducts.map(tp => tp.totalQuantityOrdered), 1);
                const pct = (p.totalQuantityOrdered / maxQty) * 100;

                return (
                  <div key={idx} className="space-y-2">
                    <div className="flex justify-between text-xs">
                      <span className="font-semibold text-gray-200">{p.name}</span>
                      <span className="font-mono text-indigo-400 font-semibold">{p.totalQuantityOrdered} units</span>
                    </div>
                    <div className="h-2 w-full bg-gray-800/60 rounded-full overflow-hidden border border-gray-700/10">
                      <div className="h-full bg-indigo-500 rounded-full transition-all duration-500 ease-out" style={{ width: `${pct}%` }} />
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* Chart Card 2: Order Status Distribution (Horizontal lists comparing state progress) */}
        <div className="lg:col-span-1 glass-panel p-6 rounded-2xl border border-gray-850">
          <div className="flex items-center justify-between border-b border-gray-850 pb-4 mb-5">
            <h4 className="text-xs font-bold text-white uppercase tracking-wider m-0 flex items-center gap-2">
              <BarChart3 className="h-4.5 w-4.5 text-indigo-400" />
              <span>Order State Distribution</span>
            </h4>
            <span className="text-[9px] text-gray-500 uppercase font-mono">FSM Progress</span>
          </div>

          <div className="space-y-4">
            {totalOrders === 0 ? (
              <div className="py-12 text-center text-xs text-gray-500">No registered orders.</div>
            ) : (
              Object.entries(metrics.orderStatusCounts).map(([status, count]) => {
                const pct = (count / totalOrders) * 100;
                return (
                  <div key={status} className="space-y-1.5">
                    <div className="flex justify-between text-[11px]">
                      <span className="font-semibold text-gray-300">{status.replace('_', ' ')}</span>
                      <span className="font-mono text-gray-400">{count} ({pct.toFixed(0)}%)</span>
                    </div>
                    <div className="h-2 w-full bg-gray-850 rounded-full overflow-hidden">
                      <div className={`h-full rounded-full ${getStatusColor(status)} transition-all duration-500`} style={{ width: `${pct}%` }} />
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>

      {/* Warehouse Stock Counts comparison row */}
      <div className="glass-panel p-6 rounded-2xl border border-gray-850">
        <div className="flex items-center justify-between border-b border-gray-850 pb-4 mb-5">
          <h4 className="text-xs font-bold text-white uppercase tracking-wider m-0 flex items-center gap-2">
            <Warehouse className="h-4.5 w-4.5 text-indigo-400" />
            <span>Warehouse Inventory Distribution</span>
          </h4>
          <span className="text-[9px] text-gray-500 uppercase font-mono">Stored item volume</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {Object.entries(metrics.warehouseStockCounts).length === 0 ? (
            <div className="col-span-full py-6 text-center text-xs text-gray-500">No warehouse data available.</div>
          ) : (
            Object.entries(metrics.warehouseStockCounts).map(([whName, whQty]) => {
              const maxWhStock = Math.max(...Object.values(metrics.warehouseStockCounts), 1);
              const pct = (whQty / maxWhStock) * 100;
              
              return (
                <div key={whName} className="p-4 bg-gray-950/20 border border-gray-850 rounded-xl space-y-3">
                  <div className="flex justify-between items-start gap-3">
                    <div>
                      <span className="font-semibold text-white text-xs block truncate max-w-[180px]" title={whName}>{whName}</span>
                      <span className="text-[9px] text-gray-500 font-mono">Facility Inventory Pool</span>
                    </div>
                    <span className="font-mono text-indigo-400 text-xs font-bold shrink-0">{whQty.toLocaleString()} units</span>
                  </div>
                  
                  <div className="h-1.5 w-full bg-gray-800 rounded-full overflow-hidden">
                    <div className="h-full bg-indigo-500 rounded-full transition-all duration-500" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
