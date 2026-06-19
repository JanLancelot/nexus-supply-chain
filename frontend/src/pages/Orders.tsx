import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { 
  Plus, 
  Search, 
  AlertTriangle, 
  X, 
  RefreshCw,
  ClipboardList,
  User,
  Building,
  DollarSign,
  ArrowRight,
  ChevronRight,
  CheckCircle,
  Truck,
  Sparkles,
  Info
} from 'lucide-react';
import { 
  getOrders, 
  createOrder, 
  updateOrderStatus, 
  type CreateOrderData 
} from '../services/orders';
import { getSuppliers, type Supplier } from '../services/suppliers';
import { getWarehouses, getProducts, type Warehouse } from '../services/products';
import { type Order, type Product } from '../types';

const Orders: React.FC = () => {
  const { isAdmin } = useAuth();

  // Master Data State
  const [orders, setOrders] = useState<Order[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // UI State
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  
  // Navigation: list, create, details
  const [viewMode, setViewMode] = useState<'list' | 'create' | 'details'>('list');
  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
  const [selectedOrder, setSelectedOrder] = useState<Order | null>(null);

  // Create Order Wizard State
  const [wizardOrder, setWizardOrder] = useState<CreateOrderData>({
    supplierId: '',
    warehouseId: '',
    items: [{ productId: '', quantity: 1 }]
  });

  const [submitting, setSubmitting] = useState(false);

  // Load Initial Data
  const loadOrdersData = async (page = currentPage) => {
    setLoading(true);
    setError(null);
    try {
      const [ordersPaged, suppliersData, warehousesData, productsPaged] = await Promise.all([
        getOrders(page, 50),
        getSuppliers(),
        getWarehouses(),
        getProducts(0, 100)
      ]);
      setOrders(ordersPaged.content);
      setTotalPages(ordersPaged.totalPages);
      setTotalElements(ordersPaged.totalElements);
      setSuppliers(suppliersData);
      setWarehouses(warehousesData);
      setProducts(productsPaged.content);
    } catch (err: any) {
      console.error(err);
      setError('Failed to fetch purchase orders data. Make sure the backend service is running.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadOrdersData(currentPage);
  }, [currentPage]);

  // Sync details if selectedOrderId is set
  useEffect(() => {
    if (selectedOrderId) {
      const found = orders.find(o => o.id === selectedOrderId);
      if (found) {
        setSelectedOrder(found);
      }
    } else {
      setSelectedOrder(null);
    }
  }, [selectedOrderId, orders]);

  // Status badge style resolver
  const getStatusBadgeStyle = (status: string) => {
    switch (status) {
      case 'DRAFT':
        return 'bg-gray-500/10 text-gray-400 border-gray-500/20';
      case 'PENDING_APPROVAL':
        return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
      case 'APPROVED':
        return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
      case 'SHIPPED':
        return 'bg-purple-500/10 text-purple-400 border-purple-500/20';
      case 'DELIVERED':
        return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
      case 'CANCELLED':
        return 'bg-red-500/10 text-red-400 border-red-500/20';
      default:
        return 'bg-gray-500/10 text-gray-400 border-gray-500/20';
    }
  };

  // State Transition handler
  const handleTransition = async (targetStatus: 'PENDING_APPROVAL' | 'APPROVED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED') => {
    if (!selectedOrder) return;
    setSubmitting(true);
    setError(null);
    try {
      const updated = await updateOrderStatus(selectedOrder.id, { status: targetStatus });
      
      // Update local orders state
      setOrders(prev => prev.map(o => o.id === updated.id ? updated : o));
      setSuccessMsg(`Order ${updated.orderNumber} successfully advanced to ${targetStatus}`);
      
      // Also update selectedOrder details immediately
      setSelectedOrder(updated);
    } catch (err: any) {
      console.error(err);
      setError(typeof err === 'string' ? err : err.response?.data?.message || 'Unauthorized state transition rejected by FSM rules.');
    } finally {
      setSubmitting(false);
    }
  };

  // Wizard Item Changes
  const handleWizardItemChange = (index: number, field: 'productId' | 'quantity', value: any) => {
    const newItems = [...wizardOrder.items];
    if (field === 'productId') {
      newItems[index].productId = value;
    } else if (field === 'quantity') {
      newItems[index].quantity = parseInt(value) || 1;
    }
    setWizardOrder({ ...wizardOrder, items: newItems });
  };

  const addWizardItem = () => {
    setWizardOrder({
      ...wizardOrder,
      items: [...wizardOrder.items, { productId: '', quantity: 1 }]
    });
  };

  const removeWizardItem = (index: number) => {
    if (wizardOrder.items.length === 1) return;
    const newItems = wizardOrder.items.filter((_, i) => i !== index);
    setWizardOrder({ ...wizardOrder, items: newItems });
  };

  // Compute total amount in real-time
  const calculateWizardTotal = () => {
    let total = 0;
    wizardOrder.items.forEach(item => {
      const prod = products.find(p => p.id === item.productId);
      if (prod) {
        total += prod.unitPrice * item.quantity;
      }
    });
    return total;
  };

  // Submit Wizard Form
  const handleCreateOrderSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    
    if (!wizardOrder.supplierId || !wizardOrder.warehouseId) {
      setError('Please select a supplier and a warehouse.');
      return;
    }

    const hasEmptyProduct = wizardOrder.items.some(item => !item.productId || item.quantity <= 0);
    if (hasEmptyProduct) {
      setError('Please select a valid product and positive quantity for all items.');
      return;
    }

    setSubmitting(true);
    try {
      const created = await createOrder(wizardOrder);
      setOrders(prev => [created, ...prev]);
      setSuccessMsg(`Purchase Order ${created.orderNumber} created successfully as DRAFT.`);
      setViewMode('list');
      setWizardOrder({
        supplierId: '',
        warehouseId: '',
        items: [{ productId: '', quantity: 1 }]
      });
    } catch (err: any) {
      console.error(err);
      setError(typeof err === 'string' ? err : 'Failed to create purchase order. Check fields.');
    } finally {
      setSubmitting(false);
    }
  };

  // Search/Filter logic
  const filteredOrders = orders.filter((order) => {
    const matchesSearch = 
      order.orderNumber.toLowerCase().includes(searchTerm.toLowerCase()) ||
      order.supplierName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      order.warehouseName.toLowerCase().includes(searchTerm.toLowerCase());

    const matchesStatus = 
      statusFilter === 'ALL' || 
      order.status === statusFilter;

    return matchesSearch && matchesStatus;
  });

  return (
    <div className="space-y-6">
      {/* Messages */}
      {error && (
        <div className="p-4 bg-red-950/40 border border-red-500/25 text-red-300 text-sm rounded-xl flex items-center justify-between">
          <div className="flex items-center gap-3">
            <AlertTriangle className="h-5 w-5 text-red-400 shrink-0" />
            <span>{error}</span>
          </div>
          <button onClick={() => setError(null)} className="text-red-400 hover:text-white cursor-pointer"><X className="h-4 w-4" /></button>
        </div>
      )}

      {successMsg && (
        <div className="p-4 bg-emerald-950/40 border border-emerald-500/25 text-emerald-300 text-sm rounded-xl flex items-center justify-between">
          <div className="flex items-center gap-3">
            <CheckSquareIcon className="h-5 w-5 text-emerald-400 shrink-0" />
            <span>{successMsg}</span>
          </div>
          <button onClick={() => setSuccessMsg(null)} className="text-emerald-400 hover:text-white cursor-pointer"><X className="h-4 w-4" /></button>
        </div>
      )}

      {/* VIEW 1: ORDERS LIST */}
      {viewMode === 'list' && (
        <>
          {/* Controls */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 glass-panel p-5 rounded-2xl">
            {/* Search */}
            <div className="relative flex-1 max-w-md">
              <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
                <Search className="h-4 w-4" />
              </span>
              <input
                type="text"
                placeholder="Search by Order Number, Supplier, Warehouse..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-9 pr-4 py-2 text-xs glass-input"
              />
            </div>

            {/* Filters */}
            <div className="flex items-center gap-3">
              <span className="text-xs text-gray-400 font-medium hidden sm:inline">Status:</span>
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className="px-3 py-2 text-xs glass-input cursor-pointer"
              >
                <option value="ALL">All Statuses</option>
                <option value="DRAFT">Draft</option>
                <option value="PENDING_APPROVAL">Pending Approval</option>
                <option value="APPROVED">Approved</option>
                <option value="SHIPPED">Shipped</option>
                <option value="DELIVERED">Delivered</option>
                <option value="CANCELLED">Cancelled</option>
              </select>

              <button
                onClick={() => loadOrdersData()}
                className="p-2 text-gray-400 hover:text-white hover:bg-gray-800/40 rounded-lg border border-gray-800 hover:border-gray-700 transition cursor-pointer"
                title="Refresh Orders List"
              >
                <RefreshCw className="h-4 w-4" />
              </button>

              <button
                onClick={() => setViewMode('create')}
                className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white font-medium text-xs rounded-lg hover:bg-indigo-500 transition cursor-pointer border border-indigo-500/20"
              >
                <Plus className="h-4 w-4" />
                <span>Create Purchase Order</span>
              </button>
            </div>
          </div>

          {/* List Data Table */}
          {loading ? (
            <div className="py-20 flex flex-col items-center justify-center">
              <div className="h-8 w-8 border-4 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
              <p className="text-sm text-gray-400">Loading purchase orders...</p>
            </div>
          ) : filteredOrders.length === 0 ? (
            <div className="py-20 text-center glass-panel rounded-2xl">
              <ClipboardList className="h-10 w-10 text-gray-600 mx-auto mb-3" />
              <h4 className="text-sm font-semibold text-white m-0">No Purchase Orders Found</h4>
              <p className="text-xs text-gray-500 mt-1">Try launching a new procurement wizard draft.</p>
            </div>
          ) : (
            <div className="glass-panel rounded-2xl overflow-hidden border border-gray-800/60 shadow-lg">
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse">
                  <thead>
                    <tr className="bg-gray-900/40 text-gray-400 text-[10px] font-semibold uppercase tracking-wider border-b border-gray-850">
                      <th className="py-4 px-6">Order Number</th>
                      <th className="py-4 px-6">Supplier</th>
                      <th className="py-4 px-6">Destination Warehouse</th>
                      <th className="py-4 px-6">Total Amount</th>
                      <th className="py-4 px-6">Status Badge</th>
                      <th className="py-4 px-6 text-center">Action</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-850 text-xs">
                    {filteredOrders.map((order) => (
                      <tr key={order.id} className="hover:bg-gray-900/10 transition-colors duration-150">
                        <td className="py-4 px-6 font-mono text-indigo-400 font-semibold">{order.orderNumber}</td>
                        <td className="py-4 px-6 text-white font-medium">{order.supplierName}</td>
                        <td className="py-4 px-6 text-gray-300">{order.warehouseName}</td>
                        <td className="py-4 px-6 text-gray-300 font-mono">${order.totalAmount.toFixed(2)}</td>
                        <td className="py-4 px-6">
                          <span className={`inline-block px-2.5 py-0.5 rounded text-[9px] font-bold border uppercase ${getStatusBadgeStyle(order.status)}`}>
                            {order.status.replace('_', ' ')}
                          </span>
                        </td>
                        <td className="py-4 px-6 text-center">
                          <button
                            onClick={() => {
                              setSelectedOrderId(order.id);
                              setViewMode('details');
                            }}
                            className="text-[11px] text-indigo-400 hover:text-indigo-300 font-semibold flex items-center gap-1.5 mx-auto cursor-pointer"
                          >
                            <span>Inspect</span>
                            <ChevronRight className="h-4 w-4" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {/* Pagination Controls */}
              {totalPages > 1 && (
                <div className="flex items-center justify-between px-6 py-4 border-t border-gray-800/60 bg-gray-900/10">
                  <div className="text-xs text-gray-400">
                    Showing page <span className="font-semibold text-white">{currentPage + 1}</span> of{' '}
                    <span className="font-semibold text-white">{totalPages}</span> ({totalElements} total orders)
                  </div>
                  <div className="flex gap-2">
                    <button
                      onClick={() => setCurrentPage((prev) => Math.max(0, prev - 1))}
                      disabled={currentPage === 0}
                      className="px-3 py-1.5 rounded bg-gray-850 text-gray-300 hover:text-white border border-gray-750 text-xs font-medium transition disabled:opacity-40 cursor-pointer disabled:cursor-not-allowed"
                    >
                      Previous
                    </button>
                    <button
                      onClick={() => setCurrentPage((prev) => Math.min(totalPages - 1, prev + 1))}
                      disabled={currentPage === totalPages - 1}
                      className="px-3 py-1.5 rounded bg-gray-850 text-gray-300 hover:text-white border border-gray-750 text-xs font-medium transition disabled:opacity-40 cursor-pointer disabled:cursor-not-allowed"
                    >
                      Next
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {/* VIEW 2: CREATE ORDER WIZARD */}
      {viewMode === 'create' && (
        <div className="w-full max-w-4xl mx-auto glass-panel rounded-2xl overflow-hidden border border-gray-800 shadow-2xl">
          <div className="p-5 border-b border-gray-800 flex items-center justify-between bg-gray-900/35">
            <div>
              <span className="font-bold text-sm text-white">New Purchase Order Execution Wizard</span>
              <p className="text-[10px] text-gray-400 mt-1">Submit draft procurement lists to trigger review gates.</p>
            </div>
            <button
              onClick={() => setViewMode('list')}
              className="px-3 py-1.5 rounded-lg border border-gray-700 text-gray-300 hover:bg-gray-800 text-xs font-medium cursor-pointer"
            >
              Back to List
            </button>
          </div>

          <form onSubmit={handleCreateOrderSubmit} className="p-6 space-y-6">
            {/* Header info */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 p-4 bg-gray-900/10 border border-gray-850 rounded-xl">
              <div>
                <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Select Supplier *</label>
                <select
                  required
                  value={wizardOrder.supplierId}
                  onChange={(e) => setWizardOrder({ ...wizardOrder, supplierId: e.target.value })}
                  className="w-full px-3 py-2.5 text-xs glass-input cursor-pointer"
                >
                  <option value="">Choose Supplier</option>
                  {suppliers.map((s) => (
                    <option key={s.id} value={s.id}>{s.name}</option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Destination Facility *</label>
                <select
                  required
                  value={wizardOrder.warehouseId}
                  onChange={(e) => setWizardOrder({ ...wizardOrder, warehouseId: e.target.value })}
                  className="w-full px-3 py-2.5 text-xs glass-input cursor-pointer"
                >
                  <option value="">Choose Warehouse</option>
                  {warehouses.map((w) => (
                    <option key={w.id} value={w.id}>{w.name}</option>
                  ))}
                </select>
              </div>
            </div>

            {/* Items block */}
            <div className="space-y-4">
              <div className="flex items-center justify-between border-b border-gray-800 pb-2">
                <span className="text-xs font-bold text-white uppercase tracking-wider">Itemized Allocations</span>
                <button
                  type="button"
                  onClick={addWizardItem}
                  className="flex items-center gap-1.5 px-3 py-1 bg-indigo-600/10 hover:bg-indigo-600 text-indigo-400 hover:text-white text-[11px] font-semibold rounded border border-indigo-500/20 transition cursor-pointer"
                >
                  <Plus className="h-3.5 w-3.5" />
                  <span>Add Line</span>
                </button>
              </div>

              <div className="space-y-3">
                {wizardOrder.items.map((item, idx) => {
                  const selectedProd = products.find(p => p.id === item.productId);
                  const subtotal = selectedProd ? selectedProd.unitPrice * item.quantity : 0.00;

                  return (
                    <div key={idx} className="flex flex-col md:flex-row items-stretch md:items-center gap-4 p-3 bg-gray-950/20 border border-gray-850 rounded-lg">
                      {/* Product select */}
                      <div className="flex-1">
                        <select
                          required
                          value={item.productId}
                          onChange={(e) => handleWizardItemChange(idx, 'productId', e.target.value)}
                          className="w-full px-3 py-2 text-xs glass-input cursor-pointer"
                        >
                          <option value="">Select SKU Product</option>
                          {products.map((p) => (
                            <option key={p.id} value={p.id}>
                              {p.sku} - {p.name} (${p.unitPrice.toFixed(2)})
                            </option>
                          ))}
                        </select>
                      </div>

                      {/* Quantity select */}
                      <div className="w-full md:w-32">
                        <input
                          type="number"
                          min="1"
                          required
                          placeholder="Qty"
                          value={item.quantity}
                          onChange={(e) => handleWizardItemChange(idx, 'quantity', e.target.value)}
                          className="w-full px-3 py-2 text-xs glass-input text-center font-mono"
                        />
                      </div>

                      {/* Line Subtotal */}
                      <div className="w-full md:w-32 flex items-center justify-between md:justify-end text-right px-2">
                        <span className="text-[10px] text-gray-500 md:hidden font-semibold">Subtotal:</span>
                        <span className="font-mono text-gray-300 font-semibold">${subtotal.toFixed(2)}</span>
                      </div>

                      {/* Remove line */}
                      <button
                        type="button"
                        onClick={() => removeWizardItem(idx)}
                        disabled={wizardOrder.items.length === 1}
                        className="p-2 text-red-400 hover:bg-red-500/10 disabled:opacity-30 rounded transition cursor-pointer self-end md:self-center"
                        title="Remove Line Item"
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Total calculation panel */}
            <div className="flex items-center justify-between p-4 bg-gray-900/40 border border-gray-800 rounded-xl">
              <div>
                <span className="text-xs text-gray-400 font-medium">Estimated Purchase Total</span>
                <p className="text-[10px] text-indigo-400 font-mono mt-0.5 uppercase">Draft Ledger</p>
              </div>
              <span className="text-2xl font-extrabold text-white font-mono">${calculateWizardTotal().toFixed(2)}</span>
            </div>

            {/* Submission buttons */}
            <div className="pt-4 border-t border-gray-800 flex items-center justify-end gap-3">
              <button
                type="button"
                onClick={() => setViewMode('list')}
                className="px-4 py-2 rounded-lg bg-transparent hover:bg-gray-800 text-gray-300 text-xs font-medium border border-gray-700 transition cursor-pointer"
              >
                Discard
              </button>
              <button
                type="submit"
                disabled={submitting}
                className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-xs font-medium border border-indigo-500/20 transition cursor-pointer"
              >
                {submitting ? 'Generating...' : 'Save Draft Order'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* VIEW 3: ORDER INSPECTION DETAIL VIEW */}
      {viewMode === 'details' && selectedOrder && (
        <div className="w-full max-w-4xl mx-auto space-y-6">
          {/* Header Navigation */}
          <div className="flex items-center justify-between">
            <button
              onClick={() => {
                setViewMode('list');
                setSelectedOrderId(null);
              }}
              className="px-3 py-1.5 rounded-lg border border-gray-700 text-gray-300 hover:bg-gray-800 text-xs font-medium cursor-pointer"
            >
              Back to List
            </button>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-400">Order ID:</span>
              <span className="text-xs text-gray-500 font-mono font-semibold break-all">{selectedOrder.id}</span>
            </div>
          </div>

          {/* Stepper Progression visual component */}
          <div className="glass-panel p-6 rounded-2xl border border-gray-800">
            <h4 className="text-xs font-bold text-white uppercase tracking-wider mb-6">Order Lifecycle Journey</h4>
            
            {/* Steps line */}
            <div className="relative flex justify-between items-center max-w-3xl mx-auto">
              {/* Stepper backgrounds */}
              <div className="absolute top-1/2 left-0 right-0 h-1 bg-gray-800 -translate-y-1/2 z-0" />
              
              {/* Progress active highlight */}
              {(() => {
                const statuses = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SHIPPED', 'DELIVERED'];
                const curIdx = statuses.indexOf(selectedOrder.status);
                const pct = curIdx === -1 ? 0 : (curIdx / (statuses.length - 1)) * 100;
                
                return (
                  <div 
                    className="absolute top-1/2 left-0 h-1 bg-indigo-500 -translate-y-1/2 z-0 transition-all duration-500 ease-in-out" 
                    style={{ width: `${selectedOrder.status === 'CANCELLED' ? 0 : pct}%` }}
                  />
                );
              })()}

              {/* Step points */}
              {['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SHIPPED', 'DELIVERED'].map((step, idx) => {
                const statuses = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SHIPPED', 'DELIVERED'];
                const curIdx = statuses.indexOf(selectedOrder.status);
                
                const isCompleted = idx < curIdx && selectedOrder.status !== 'CANCELLED';
                const isActive = step === selectedOrder.status;

                let pointColor = 'bg-gray-900 border-gray-850 text-gray-500';
                if (isActive) {
                  pointColor = 'bg-indigo-600 border-indigo-400 text-white shadow-lg shadow-indigo-600/30 scale-110';
                } else if (isCompleted) {
                  pointColor = 'bg-indigo-950 border-indigo-500 text-indigo-400';
                }

                return (
                  <div key={step} className="flex flex-col items-center z-10">
                    <div className={`h-8 w-8 rounded-full border-2 flex items-center justify-center font-mono text-xs font-bold transition duration-300 ${pointColor}`}>
                      {idx + 1}
                    </div>
                    <span className={`text-[9px] font-bold tracking-wide mt-2 uppercase transition duration-300 ${isActive ? 'text-indigo-400' : 'text-gray-500'}`}>
                      {step.replace('_', ' ')}
                    </span>
                  </div>
                );
              })}
            </div>

            {selectedOrder.status === 'CANCELLED' && (
              <div className="mt-6 p-3 bg-red-950/20 border border-red-500/15 rounded-xl text-center text-xs text-red-300 flex items-center justify-center gap-2 max-w-md mx-auto">
                <AlertTriangle className="h-4.5 w-4.5 text-red-400" />
                <span>This purchase order has been **CANCELLED** and is void.</span>
              </div>
            )}
          </div>

          {/* Main Inspection Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            {/* Left panel: Info summary */}
            <div className="lg:col-span-1 space-y-6">
              {/* Summary card */}
              <div className="glass-panel p-6 rounded-2xl border border-gray-850 space-y-4">
                <h4 className="text-xs font-bold text-white uppercase tracking-wider border-b border-gray-850 pb-2">Order Metadata</h4>
                
                <div className="space-y-3.5 text-xs text-gray-400">
                  <div className="flex justify-between">
                    <span className="flex items-center gap-1.5"><ClipboardList className="h-4 w-4 text-gray-500" /> Number:</span>
                    <span className="font-mono text-white font-semibold">{selectedOrder.orderNumber}</span>
                  </div>
                  
                  <div className="flex justify-between">
                    <span className="flex items-center gap-1.5"><Building className="h-4 w-4 text-gray-500" /> Supplier:</span>
                    <span className="text-white font-medium">{selectedOrder.supplierName}</span>
                  </div>

                  <div className="flex justify-between">
                    <span className="flex items-center gap-1.5"><Building className="h-4 w-4 text-gray-500" /> Facility:</span>
                    <span className="text-white font-medium">{selectedOrder.warehouseName}</span>
                  </div>

                  <div className="flex justify-between">
                    <span className="flex items-center gap-1.5"><User className="h-4 w-4 text-gray-500" /> Originator:</span>
                    <span className="text-indigo-400 font-mono truncate max-w-[120px]" title={selectedOrder.createdBy}>{selectedOrder.createdBy}</span>
                  </div>

                  <div className="flex justify-between">
                    <span className="flex items-center gap-1.5"><DollarSign className="h-4 w-4 text-gray-500" /> Net Total:</span>
                    <span className="font-mono text-white font-semibold">${selectedOrder.totalAmount.toFixed(2)}</span>
                  </div>
                </div>
              </div>

              {/* FSM CONTROLLER BUTTONS PANEL */}
              <div className="glass-panel p-6 rounded-2xl border border-gray-850 space-y-4 bg-gray-900/10">
                <h4 className="text-xs font-bold text-white uppercase tracking-wider border-b border-gray-850 pb-2">Lifecycle Controller</h4>
                
                {/* Logic gate details */}
                <div className="space-y-3.5">
                  {/* Draft State */}
                  {selectedOrder.status === 'DRAFT' && (
                    <div className="space-y-2.5">
                      <button
                        onClick={() => handleTransition('PENDING_APPROVAL')}
                        disabled={submitting}
                        className="w-full py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white border border-indigo-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                      >
                        <span>Submit for Approval</span>
                        <ArrowRight className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleTransition('CANCELLED')}
                        disabled={submitting}
                        className="w-full py-2.5 bg-transparent hover:bg-red-500/10 text-red-400 hover:text-red-300 border border-gray-700 hover:border-red-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                      >
                        <X className="h-4 w-4" />
                        <span>Cancel Order</span>
                      </button>
                    </div>
                  )}

                  {/* Pending Approval State */}
                  {selectedOrder.status === 'PENDING_APPROVAL' && (
                    <div className="space-y-2.5">
                      {isAdmin ? (
                        <>
                          <button
                            onClick={() => handleTransition('APPROVED')}
                            disabled={submitting}
                            className="w-full py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white border border-emerald-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                          >
                            <CheckCircle className="h-4 w-4" />
                            <span>Approve & Verify</span>
                          </button>
                          <button
                            onClick={() => handleTransition('CANCELLED')}
                            disabled={submitting}
                            className="w-full py-2.5 bg-transparent hover:bg-red-500/10 text-red-400 hover:text-red-300 border border-gray-700 hover:border-red-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                          >
                            <X className="h-4 w-4" />
                            <span>Reject & Cancel</span>
                          </button>
                        </>
                      ) : (
                        <div className="p-3.5 bg-gray-950/20 border border-gray-850 rounded-xl flex items-start gap-2.5 text-[11px] text-gray-400 leading-normal">
                          <Info className="h-4.5 w-4.5 text-amber-400 shrink-0 mt-0.5" />
                          <div>
                            <span className="font-semibold text-white block">Awaiting Review</span>
                            Staff operators cannot approve orders. Pending administrative evaluation.
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                  {/* Approved State */}
                  {selectedOrder.status === 'APPROVED' && (
                    <div className="space-y-2.5">
                      {isAdmin ? (
                        <>
                          <button
                            onClick={() => handleTransition('SHIPPED')}
                            disabled={submitting}
                            className="w-full py-2.5 bg-indigo-600 hover:bg-indigo-500 text-white border border-indigo-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                          >
                            <Truck className="h-4 w-4" />
                            <span>Dispatch / Ship</span>
                          </button>
                          <button
                            onClick={() => handleTransition('CANCELLED')}
                            disabled={submitting}
                            className="w-full py-2.5 bg-transparent hover:bg-red-500/10 text-red-400 hover:text-red-300 border border-gray-700 hover:border-red-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                          >
                            <X className="h-4 w-4" />
                            <span>Cancel Order</span>
                          </button>
                        </>
                      ) : (
                        <div className="p-3.5 bg-gray-950/20 border border-gray-850 rounded-xl flex items-start gap-2.5 text-[11px] text-gray-400 leading-normal">
                          <Info className="h-4.5 w-4.5 text-indigo-400 shrink-0 mt-0.5" />
                          <div>
                            <span className="font-semibold text-white block">Order Approved</span>
                            Admin approval logged. Dispatch queue locks until shipping dispatch.
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                  {/* Shipped State */}
                  {selectedOrder.status === 'SHIPPED' && (
                    <div className="space-y-2.5">
                      {isAdmin ? (
                        <>
                          <button
                            onClick={() => handleTransition('DELIVERED')}
                            disabled={submitting}
                            className="w-full py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white border border-emerald-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                          >
                            <CheckCircle className="h-4 w-4" />
                            <span>Confirm Delivery</span>
                          </button>
                          <button
                            onClick={() => handleTransition('CANCELLED')}
                            disabled={submitting}
                            className="w-full py-2.5 bg-transparent hover:bg-red-500/10 text-red-400 hover:text-red-300 border border-gray-700 hover:border-red-500/20 text-xs font-semibold rounded-lg transition cursor-pointer flex items-center justify-center gap-2"
                          >
                            <X className="h-4 w-4" />
                            <span>Cancel Order</span>
                          </button>
                        </>
                      ) : (
                        <div className="p-3.5 bg-gray-950/20 border border-gray-850 rounded-xl flex items-start gap-2.5 text-[11px] text-gray-400 leading-normal">
                          <Info className="h-4.5 w-4.5 text-purple-400 shrink-0 mt-0.5" />
                          <div>
                            <span className="font-semibold text-white block">In Transit</span>
                            Materials shipped. Reception ledger requires Admin verification upon cargo arrival.
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                  {/* Terminal States (DELIVERED, CANCELLED) */}
                  {(selectedOrder.status === 'DELIVERED' || selectedOrder.status === 'CANCELLED') && (
                    <div className="p-4 bg-gray-950/20 border border-gray-850 rounded-xl text-center text-xs text-gray-400">
                      <Sparkles className="h-5 w-5 text-indigo-400 mx-auto mb-2" />
                      <span>Order Lifecycle Concluded</span>
                      <p className="text-[10px] text-gray-500 mt-1">Status changes are locked.</p>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* Right panel: Itemized allocations details list */}
            <div className="lg:col-span-2 space-y-6">
              <div className="glass-panel rounded-2xl overflow-hidden border border-gray-850 shadow-lg">
                <div className="p-4 bg-gray-900/35 border-b border-gray-850">
                  <h4 className="text-xs font-bold text-white uppercase tracking-wider m-0">Itemized Allocation Listing</h4>
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs">
                    <thead>
                      <tr className="bg-gray-900/20 text-gray-500 text-[10px] font-semibold uppercase tracking-wider border-b border-gray-850">
                        <th className="py-3.5 px-6">Product Item</th>
                        <th className="py-3.5 px-6 font-mono text-center">Qty</th>
                        <th className="py-3.5 px-6 text-right">Unit Price</th>
                        <th className="py-3.5 px-6 text-right">Subtotal</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-850">
                      {selectedOrder.items.map((item, idx) => {
                        const productDetail = products.find(p => p.id === item.productId);
                        return (
                          <tr key={idx} className="hover:bg-gray-900/5 transition">
                            <td className="py-4 px-6">
                              <div className="font-semibold text-white">{productDetail?.name || 'Unknown Product'}</div>
                              <div className="text-[10px] text-indigo-400 font-mono mt-0.5">{productDetail?.sku || 'N/A'}</div>
                            </td>
                            <td className="py-4 px-6 text-center font-mono text-gray-200 font-semibold">{item.quantity}</td>
                            <td className="py-4 px-6 text-right font-mono text-gray-400">${item.unitPrice.toFixed(2)}</td>
                            <td className="py-4 px-6 text-right font-mono text-white font-semibold">${item.subtotal.toFixed(2)}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// Helper check mark icon inside success message
const CheckSquareIcon: React.FC<React.SVGProps<SVGSVGElement>> = (props) => (
  <svg
    {...props}
    xmlns="http://www.w3.org/2000/svg"
    width="24"
    height="24"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <polyline points="9 11 12 14 22 4" />
    <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
  </svg>
);

export default Orders;
