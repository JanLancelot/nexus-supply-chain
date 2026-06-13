import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { 
  History, 
  Search, 
  X, 
  RefreshCw,
  Eye,
  Calendar,
  User,
  ShieldAlert
} from 'lucide-react';
import { getAuditLogs } from '../services/audit';
import { type AuditLog } from '../types';

const AuditLogs: React.FC = () => {
  useAuth();
  
  // State variables
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  // Selection/Inspect details
  const [selectedLog, setSelectedLog] = useState<AuditLog | null>(null);
  const [showDetailPanel, setShowDetailPanel] = useState(false);

  // Search & Filter State
  const [searchTerm, setSearchTerm] = useState('');
  const [entityFilter, setEntityFilter] = useState('ALL');
  const [actionFilter, setActionFilter] = useState('ALL');

  const fetchLogs = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAuditLogs();
      // Sort: newest first
      const sorted = [...data].sort((a, b) => 
        new Date(b.createdAt || '').getTime() - new Date(a.createdAt || '').getTime()
      );
      setLogs(sorted);
    } catch (err: any) {
      console.error(err);
      setError('Access Denied or Failed to fetch forensic logs. Check administrative authorization token.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs();
  }, []);

  // Action tag color resolver
  const getActionBadgeStyle = (action: string) => {
    switch (action) {
      case 'ACTION_MANUAL_ADJUSTMENT':
        return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
      case 'ACTION_CREATE_ORDER':
        return 'bg-purple-500/10 text-purple-400 border-purple-500/20';
      case 'ACTION_UPDATE_ORDER_STATUS':
        return 'bg-indigo-500/10 text-indigo-400 border-indigo-500/20';
      default:
        return 'bg-gray-500/10 text-gray-400 border-gray-550/20';
    }
  };

  // Safe JSON parser helper for diff panel
  const parseJsonSafe = (jsonStr: string | null) => {
    if (!jsonStr) return {};
    try {
      // The old/new values in backend might be stringified JSON like '{"stockQuantity": 1240}'
      return JSON.parse(jsonStr);
    } catch (e) {
      // Return raw string mapping if it fails to parse as JSON
      return { value: jsonStr };
    }
  };

  // Diff parser that matches keys between old and new state
  const renderDiffTable = (oldValStr: string | null, newValStr: string | null) => {
    const oldObj = parseJsonSafe(oldValStr);
    const newObj = parseJsonSafe(newValStr);

    const allKeys = Array.from(new Set([...Object.keys(oldObj), ...Object.keys(newObj)]));

    if (allKeys.length === 0) {
      return (
        <span className="text-xs text-gray-500 italic">No structural properties recorded.</span>
      );
    }

    return (
      <div className="border border-gray-800 rounded-lg overflow-hidden text-xs">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-gray-950/40 text-gray-400 text-[10px] font-semibold uppercase tracking-wider border-b border-gray-850">
              <th className="py-2.5 px-4">Property</th>
              <th className="py-2.5 px-4 text-red-400">Old Value</th>
              <th className="py-2.5 px-4 text-emerald-400">New Value</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-850 text-xs font-mono">
            {allKeys.map((key) => {
              const oldVal = oldObj[key];
              const newVal = newObj[key];
              
              // Only highlight changes
              const isChanged = JSON.stringify(oldVal) !== JSON.stringify(newVal);

              return (
                <tr key={key} className={isChanged ? 'bg-indigo-500/5' : ''}>
                  <td className="py-2.5 px-4 text-gray-300 font-medium font-sans">{key}</td>
                  <td className="py-2.5 px-4 text-red-300">
                    {oldVal !== undefined ? JSON.stringify(oldVal) : <span className="text-[10px] text-gray-600">NULL</span>}
                  </td>
                  <td className="py-2.5 px-4 text-emerald-300">
                    {newVal !== undefined ? JSON.stringify(newVal) : <span className="text-[10px] text-gray-600">NULL</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    );
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    return date.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' }) + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  // Filters logic
  const filteredLogs = logs.filter((log) => {
    const matchesSearch = 
      log.entityId.toLowerCase().includes(searchTerm.toLowerCase()) || 
      (log.userId && log.userId.toLowerCase().includes(searchTerm.toLowerCase()));

    const matchesEntity = 
      entityFilter === 'ALL' || 
      log.entityType === entityFilter;

    const matchesAction = 
      actionFilter === 'ALL' || 
      log.action === actionFilter;

    return matchesSearch && matchesEntity && matchesAction;
  });

  return (
    <div className="space-y-6">
      {/* Messages */}
      {error && (
        <div className="p-4 bg-red-950/40 border border-red-500/25 text-red-300 text-sm rounded-xl flex items-center justify-between">
          <div className="flex items-center gap-3">
            <ShieldAlert className="h-5 w-5 text-red-400 shrink-0" />
            <span>{error}</span>
          </div>
          <button onClick={() => setError(null)} className="text-red-400 hover:text-white cursor-pointer"><X className="h-4 w-4" /></button>
        </div>
      )}

      {/* Main layout grid */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6 items-start">
        
        {/* Left/Middle: Table listing */}
        <div className="xl:col-span-2 space-y-6">
          {/* Controls */}
          <div className="flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4 glass-panel p-5 rounded-2xl">
            {/* Search */}
            <div className="relative flex-1 max-w-sm">
              <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
                <Search className="h-4 w-4" />
              </span>
              <input
                type="text"
                placeholder="Search Entity ID or Actor ID..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-9 pr-4 py-2 text-xs glass-input"
              />
            </div>

            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3">
              <select
                value={entityFilter}
                onChange={(e) => setEntityFilter(e.target.value)}
                className="px-3 py-2 text-xs glass-input cursor-pointer"
              >
                <option value="ALL">All Entities</option>
                <option value="Product">Products</option>
                <option value="Order">Orders</option>
              </select>

              <select
                value={actionFilter}
                onChange={(e) => setActionFilter(e.target.value)}
                className="px-3 py-2 text-xs glass-input cursor-pointer"
              >
                <option value="ALL">All Actions</option>
                <option value="ACTION_MANUAL_ADJUSTMENT">Stock Overrides</option>
                <option value="ACTION_CREATE_ORDER">Create Order</option>
                <option value="ACTION_UPDATE_ORDER_STATUS">Update Status</option>
              </select>

              <button
                onClick={fetchLogs}
                className="p-2 text-gray-400 hover:text-white hover:bg-gray-800/40 rounded-lg border border-gray-800 hover:border-gray-700 transition cursor-pointer"
                title="Refresh Audit History"
              >
                <RefreshCw className="h-4 w-4" />
              </button>
            </div>
          </div>

          {/* Table list */}
          {loading ? (
            <div className="py-20 flex flex-col items-center justify-center">
              <div className="h-8 w-8 border-4 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
              <p className="text-sm text-gray-400">Streaming ledger logs...</p>
            </div>
          ) : filteredLogs.length === 0 ? (
            <div className="py-20 text-center glass-panel rounded-2xl">
              <History className="h-10 w-10 text-gray-600 mx-auto mb-3" />
              <h4 className="text-sm font-semibold text-white m-0">No Audit Logs Recorded</h4>
              <p className="text-xs text-gray-500 mt-1">Forensic traces are completely clean.</p>
            </div>
          ) : (
            <div className="glass-panel rounded-2xl overflow-hidden border border-gray-800/60 shadow-lg">
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse text-xs">
                  <thead>
                    <tr className="bg-gray-900/40 text-gray-400 text-[10px] font-semibold uppercase tracking-wider border-b border-gray-850">
                      <th className="py-4 px-6">Timestamp</th>
                      <th className="py-4 px-6">Entity (Class)</th>
                      <th className="py-4 px-6">Action / Event</th>
                      <th className="py-4 px-6">System Actor</th>
                      <th className="py-4 px-6 text-center">Details</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-850">
                    {filteredLogs.map((log) => (
                      <tr 
                        key={log.id} 
                        className={`hover:bg-gray-900/10 transition duration-150 cursor-pointer ${selectedLog?.id === log.id ? 'bg-indigo-600/5' : ''}`}
                        onClick={() => {
                          setSelectedLog(log);
                          setShowDetailPanel(true);
                        }}
                      >
                        <td className="py-4 px-6 text-gray-400 font-mono">{formatDate(log.createdAt)}</td>
                        <td className="py-4 px-6">
                          <div className="font-semibold text-white">{log.entityType}</div>
                          <div className="text-[9px] text-gray-500 font-mono mt-0.5 truncate max-w-[120px]" title={log.entityId}>
                            ID: {log.entityId}
                          </div>
                        </td>
                        <td className="py-4 px-6">
                          <span className={`inline-block px-2 py-0.5 rounded text-[9px] font-bold border uppercase ${getActionBadgeStyle(log.action)}`}>
                            {log.action.replace('ACTION_', '').replace(/_/g, ' ')}
                          </span>
                        </td>
                        <td className="py-4 px-6 text-indigo-400 font-mono font-medium truncate max-w-[110px]" title={log.userId}>
                          {log.userId || 'SYSTEM'}
                        </td>
                        <td className="py-4 px-6 text-center">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSelectedLog(log);
                              setShowDetailPanel(true);
                            }}
                            className="text-gray-400 hover:text-white p-1 rounded hover:bg-gray-800 transition cursor-pointer"
                          >
                            <Eye className="h-4 w-4" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>

        {/* Right side: Inspection Details diff panel */}
        {showDetailPanel && selectedLog && (
          <div className="xl:col-span-1 glass-panel p-6 rounded-2xl border border-gray-850 space-y-6 sticky top-22">
            <div className="flex items-center justify-between border-b border-gray-850 pb-4">
              <div>
                <span className="font-bold text-sm text-white">Log Snapshot Details</span>
                <p className="text-[9px] text-gray-500 font-mono mt-1 break-all">Log Ref: {selectedLog.id}</p>
              </div>
              <button 
                onClick={() => setShowDetailPanel(false)}
                className="text-gray-400 hover:text-white p-1.5 rounded-md hover:bg-gray-850 cursor-pointer"
              >
                <X className="h-4.5 w-4.5" />
              </button>
            </div>

            {/* Meta details list */}
            <div className="space-y-3.5 text-xs text-gray-400">
              <div className="flex justify-between">
                <span className="flex items-center gap-1.5"><Calendar className="h-4 w-4 text-gray-500" /> Date:</span>
                <span className="text-white font-mono">{formatDate(selectedLog.createdAt)}</span>
              </div>
              <div className="flex justify-between">
                <span className="flex items-center gap-1.5"><User className="h-4 w-4 text-gray-500" /> Performed By:</span>
                <span className="text-indigo-400 font-mono truncate max-w-[180px] text-right" title={selectedLog.userId}>
                  {selectedLog.userId || 'SYSTEM'}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="flex items-center gap-1.5"><History className="h-4 w-4 text-gray-500" /> Target Entity:</span>
                <span className="text-white font-semibold text-right">{selectedLog.entityType} ({selectedLog.entityId.slice(0, 8)}...)</span>
              </div>
              <div className="flex justify-between">
                <span className="flex items-center gap-1.5"><ShieldAlert className="h-4 w-4 text-gray-500" /> Event Verb:</span>
                <span className="text-white font-semibold text-right">{selectedLog.action}</span>
              </div>
            </div>

            {/* Differential properties changes */}
            <div className="space-y-3 pt-2">
              <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider block">Differential State Logs</span>
              
              {renderDiffTable(selectedLog.oldValue, selectedLog.newValue)}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default AuditLogs;
