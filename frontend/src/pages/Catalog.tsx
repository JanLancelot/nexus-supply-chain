import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { 
  Plus, 
  Search, 
  AlertTriangle, 
  PackagePlus,
  X,
  RefreshCw,
  TrendingDown,
  Info
} from 'lucide-react';
import { 
  getProducts, 
  createProduct, 
  adjustProductStock, 
  getCategories, 
  getWarehouses,
  type CreateProductData,
  type Category,
  type Warehouse 
} from '../services/products';
import { type Product } from '../types';

const Catalog: React.FC = () => {
  const { isAdmin } = useAuth();
  
  // State variables
  const [products, setProducts] = useState<Product[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Search & Filter State
  const [searchTerm, setSearchTerm] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('ALL');
  const [stockFilter, setStockFilter] = useState('ALL'); // ALL, LOW_STOCK

  // Modals
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showAdjustModal, setShowAdjustModal] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);

  // Form states
  const [newProduct, setNewProduct] = useState<CreateProductData>({
    sku: '',
    name: '',
    reorderLevel: 10,
    categoryId: '',
    warehouseId: '',
    unitPrice: 0.00,
  });

  const [adjustment, setAdjustment] = useState({
    quantityAdjustment: 0,
    reasonCode: 'CYCLIC_COUNT_DISCREPANCY' as 'CYCLIC_COUNT_DISCREPANCY' | 'DAMAGED_GOODS_SCRAP' | 'SUPPLIER_SHORTAGE',
  });

  const [submitting, setSubmitting] = useState(false);

  // Fetch data
  const loadCatalogData = async () => {
    setLoading(true);
    setError(null);
    try {
      const [productsData, categoriesData, warehousesData] = await Promise.all([
        getProducts(),
        getCategories(),
        getWarehouses(),
      ]);
      setProducts(productsData);
      setCategories(categoriesData);
      setWarehouses(warehousesData);
    } catch (err: any) {
      console.error(err);
      setError('Failed to retrieve product catalog data. Ensure the backend is online.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCatalogData();
  }, []);

  // Form handle submit for Create Product
  const handleCreateProductSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newProduct.sku || !newProduct.name || newProduct.unitPrice <= 0) {
      setError('Please fill in all required fields and provide a valid price');
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const created = await createProduct({
        ...newProduct,
        categoryId: newProduct.categoryId || undefined,
        warehouseId: newProduct.warehouseId || undefined,
      });
      setProducts((prev) => [...prev, created]);
      setSuccessMsg(`Successfully cataloged product: ${created.sku}`);
      setShowCreateModal(false);
      // Reset form
      setNewProduct({
        sku: '',
        name: '',
        reorderLevel: 10,
        categoryId: '',
        warehouseId: '',
        unitPrice: 0.00,
      });
    } catch (err: any) {
      console.error(err);
      setError(typeof err === 'string' ? err : 'Failed to create product. Ensure the SKU is unique.');
    } finally {
      setSubmitting(false);
    }
  };

  // Form handle submit for Stock Adjustment
  const handleAdjustSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProduct) return;
    if (adjustment.quantityAdjustment === 0) {
      setError('Quantity adjustment cannot be zero.');
      return;
    }

    // Safety check: Cannot adjust below zero
    const resultingStock = selectedProduct.stockQuantity + adjustment.quantityAdjustment;
    if (resultingStock < 0) {
      setError(`Invalid adjustment. Cannot reduce stock below 0 (current: ${selectedProduct.stockQuantity}, adjustment: ${adjustment.quantityAdjustment}).`);
      return;
    }

    setSubmitting(true);
    setError(null);
    try {
      const updated = await adjustProductStock(selectedProduct.id, adjustment);
      setProducts((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
      setSuccessMsg(`Inventory adjustment successful for SKU: ${updated.sku}`);
      setShowAdjustModal(false);
      setAdjustment({
        quantityAdjustment: 0,
        reasonCode: 'CYCLIC_COUNT_DISCREPANCY',
      });
      setSelectedProduct(null);
    } catch (err: any) {
      console.error(err);
      setError(typeof err === 'string' ? err : 'Failed to adjust inventory. Check backend logs.');
    } finally {
      setSubmitting(false);
    }
  };

  // Filters logic
  const filteredProducts = products.filter((product) => {
    const matchesSearch = 
      product.name.toLowerCase().includes(searchTerm.toLowerCase()) || 
      product.sku.toLowerCase().includes(searchTerm.toLowerCase());
    
    // In our backend database categories, product has categoryId or categoryName.
    // Let's filter by checking categoryName or categoryId if available
    const matchesCategory = 
      categoryFilter === 'ALL' || 
      product.categoryId === categoryFilter || 
      product.categoryName === categoryFilter;

    const matchesStock = 
      stockFilter === 'ALL' || 
      (stockFilter === 'LOW_STOCK' && product.lowStockIndicator);

    return matchesSearch && matchesCategory && matchesStock;
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

      {/* Control Panel */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 glass-panel p-5 rounded-2xl">
        {/* Search */}
        <div className="relative flex-1 max-w-md">
          <span className="absolute inset-y-0 left-0 pl-3 flex items-center text-gray-500">
            <Search className="h-4 w-4" />
          </span>
          <input
            type="text"
            placeholder="Search by SKU or Product Name..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="w-full pl-9 pr-4 py-2 text-xs glass-input"
          />
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-3">
          {/* Category Filter */}
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-400 font-medium hidden sm:inline">Category:</span>
            <select
              value={categoryFilter}
              onChange={(e) => setCategoryFilter(e.target.value)}
              className="px-3 py-2 text-xs glass-input cursor-pointer"
            >
              <option value="ALL">All Categories</option>
              {categories.map((c) => (
                <option key={c.id} value={c.name}>{c.name}</option>
              ))}
            </select>
          </div>

          {/* Stock Alert Filter */}
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-400 font-medium hidden sm:inline">Stock Status:</span>
            <select
              value={stockFilter}
              onChange={(e) => setStockFilter(e.target.value)}
              className="px-3 py-2 text-xs glass-input cursor-pointer"
            >
              <option value="ALL">All Stock</option>
              <option value="LOW_STOCK">Low Stock Only</option>
            </select>
          </div>

          {/* Action Buttons */}
          <button
            onClick={loadCatalogData}
            className="p-2 text-gray-400 hover:text-white hover:bg-gray-800/40 rounded-lg border border-gray-800 hover:border-gray-700 transition cursor-pointer"
            title="Refresh Catalog Data"
          >
            <RefreshCw className="h-4 w-4" />
          </button>

          {isAdmin && (
            <button
              onClick={() => setShowCreateModal(true)}
              className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white font-medium text-xs rounded-lg hover:bg-indigo-500 transition cursor-pointer border border-indigo-500/20"
            >
              <Plus className="h-4 w-4" />
              <span>New Product</span>
            </button>
          )}
        </div>
      </div>

      {/* Grid List */}
      {loading ? (
        <div className="py-20 flex flex-col items-center justify-center">
          <div className="h-8 w-8 border-4 border-indigo-500/20 border-t-indigo-500 rounded-full animate-spin mb-4" />
          <p className="text-sm text-gray-400">Loading catalog items...</p>
        </div>
      ) : filteredProducts.length === 0 ? (
        <div className="py-20 text-center glass-panel rounded-2xl">
          <TrendingDown className="h-10 w-10 text-gray-600 mx-auto mb-3" />
          <h4 className="text-sm font-semibold text-white m-0">No Products Found</h4>
          <p className="text-xs text-gray-500 mt-1">Try modifying your search queries or filters.</p>
        </div>
      ) : (
        <div className="glass-panel rounded-2xl overflow-hidden border border-gray-800/60 shadow-lg">
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-gray-900/40 text-gray-400 text-[10px] font-semibold uppercase tracking-wider border-b border-gray-850">
                  <th className="py-4 px-6">SKU / Code</th>
                  <th className="py-4 px-6">Product Details</th>
                  <th className="py-4 px-6">Warehouse</th>
                  <th className="py-4 px-6">Price</th>
                  <th className="py-4 px-6">Stock Status</th>
                  <th className="py-4 px-6">Safety Reorder</th>
                  {isAdmin && <th className="py-4 px-6 text-center">Actions</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-850 text-xs">
                {filteredProducts.map((product) => (
                  <tr key={product.id} className="hover:bg-gray-900/10 transition-colors duration-150">
                    <td className="py-4 px-6 font-mono text-indigo-400 font-semibold">{product.sku}</td>
                    <td className="py-4 px-6">
                      <div className="font-semibold text-white">{product.name}</div>
                      <div className="text-[10px] text-gray-500 mt-0.5">{product.categoryName || 'No Category'}</div>
                    </td>
                    <td className="py-4 px-6 text-gray-300">{product.warehouseName || 'Global Facility'}</td>
                    <td className="py-4 px-6 text-gray-300 font-medium">${product.unitPrice.toFixed(2)}</td>
                    <td className="py-4 px-6">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-white font-mono">{product.stockQuantity}</span>
                        {product.lowStockIndicator ? (
                          <span className="flex items-center gap-1 px-2 py-0.5 rounded text-[9px] font-bold bg-amber-500/10 text-amber-400 border border-amber-500/20 shadow-sm shadow-amber-500/5">
                            <AlertTriangle className="h-3 w-3 shrink-0" />
                            <span>LOW STOCK</span>
                          </span>
                        ) : (
                          <span className="px-2 py-0.5 rounded text-[9px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                            NORMAL
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="py-4 px-6 text-gray-400 font-mono">Reorder: {product.reorderLevel}</td>
                    {isAdmin && (
                      <td className="py-4 px-6 text-center">
                        <button
                          onClick={() => {
                            setSelectedProduct(product);
                            setShowAdjustModal(true);
                          }}
                          className="px-3 py-1.5 rounded-md bg-gray-800 hover:bg-indigo-600 hover:text-white text-indigo-400 border border-gray-700/60 hover:border-indigo-500/30 text-[11px] font-medium transition cursor-pointer flex items-center gap-1.5 mx-auto"
                        >
                          <PackagePlus className="h-3.5 w-3.5" />
                          <span>Adjust</span>
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* CREATE PRODUCT MODAL */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50 animate-fade-in">
          <div className="w-full max-w-lg glass-panel rounded-2xl overflow-hidden border border-gray-800 shadow-2xl animate-scale-up">
            <div className="p-5 border-b border-gray-800 flex items-center justify-between bg-gray-900/35">
              <span className="font-bold text-sm text-white">Add New Catalog Product</span>
              <button onClick={() => setShowCreateModal(false)} className="text-gray-400 hover:text-white cursor-pointer">
                <X className="h-5 w-5" />
              </button>
            </div>
            
            <form onSubmit={handleCreateProductSubmit} className="p-6 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">SKU Code *</label>
                  <input
                    type="text"
                    required
                    placeholder="e.g. PG-TIDE-002"
                    value={newProduct.sku}
                    onChange={(e) => setNewProduct({ ...newProduct, sku: e.target.value })}
                    className="w-full px-3 py-2 text-xs glass-input"
                  />
                </div>
                <div>
                  <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Unit Price ($) *</label>
                  <input
                    type="number"
                    step="0.01"
                    min="0.01"
                    required
                    placeholder="e.g. 12.99"
                    value={newProduct.unitPrice || ''}
                    onChange={(e) => setNewProduct({ ...newProduct, unitPrice: parseFloat(e.target.value) })}
                    className="w-full px-3 py-2 text-xs glass-input"
                  />
                </div>
              </div>

              <div>
                <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Product Name *</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Tide Pods Clean Breeze 38ct"
                  value={newProduct.name}
                  onChange={(e) => setNewProduct({ ...newProduct, name: e.target.value })}
                  className="w-full px-3 py-2 text-xs glass-input"
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Category</label>
                  <select
                    value={newProduct.categoryId}
                    onChange={(e) => setNewProduct({ ...newProduct, categoryId: e.target.value })}
                    className="w-full px-3 py-2 text-xs glass-input cursor-pointer"
                  >
                    <option value="">Select Category</option>
                    {categories.map((c) => (
                      <option key={c.id} value={c.id}>{c.name}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Warehouse</label>
                  <select
                    value={newProduct.warehouseId}
                    onChange={(e) => setNewProduct({ ...newProduct, warehouseId: e.target.value })}
                    className="w-full px-3 py-2 text-xs glass-input cursor-pointer"
                  >
                    <option value="">Select Warehouse</option>
                    {warehouses.map((w) => (
                      <option key={w.id} value={w.id}>{w.name}</option>
                    ))}
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Reorder Level *</label>
                <input
                  type="number"
                  min="0"
                  required
                  placeholder="Reorder Level (Safety Margin)"
                  value={newProduct.reorderLevel}
                  onChange={(e) => setNewProduct({ ...newProduct, reorderLevel: parseInt(e.target.value) })}
                  className="w-full px-3 py-2 text-xs glass-input"
                />
              </div>

              <div className="pt-4 border-t border-gray-800 flex items-center justify-end gap-3">
                <button
                  type="button"
                  onClick={() => setShowCreateModal(false)}
                  className="px-4 py-2 rounded-lg bg-transparent hover:bg-gray-800 text-gray-300 text-xs font-medium border border-gray-700 transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-xs font-medium border border-indigo-500/20 transition cursor-pointer"
                >
                  {submitting ? 'Creating...' : 'Create Product'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ADJUST STOCK MODAL */}
      {showAdjustModal && selectedProduct && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50 animate-fade-in">
          <div className="w-full max-w-md glass-panel rounded-2xl overflow-hidden border border-gray-800 shadow-2xl animate-scale-up">
            <div className="p-5 border-b border-gray-800 flex items-center justify-between bg-gray-900/35">
              <div>
                <span className="font-bold text-sm text-white">Override Stock Quantity</span>
                <p className="text-[10px] text-gray-400 mt-1 font-mono">SKU: {selectedProduct.sku}</p>
              </div>
              <button onClick={() => setShowAdjustModal(false)} className="text-gray-400 hover:text-white cursor-pointer">
                <X className="h-5 w-5" />
              </button>
            </div>

            <form onSubmit={handleAdjustSubmit} className="p-6 space-y-4">
              <div className="p-3 bg-indigo-600/5 border border-indigo-500/10 rounded-lg flex items-start gap-3">
                <Info className="h-4.5 w-4.5 text-indigo-400 shrink-0 mt-0.5" />
                <div className="text-[11px] text-gray-300 leading-normal">
                  <span className="font-semibold text-white block">Current Stock: {selectedProduct.stockQuantity} items</span>
                  Enter a negative number (e.g. -10) to scrap stock, or a positive number (e.g. 50) to add stock.
                </div>
              </div>

              <div>
                <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Quantity Adjustment *</label>
                <input
                  type="number"
                  required
                  placeholder="e.g. -12 or 50"
                  value={adjustment.quantityAdjustment || ''}
                  onChange={(e) => setAdjustment({ ...adjustment, quantityAdjustment: parseInt(e.target.value) })}
                  className="w-full px-3 py-2 text-xs glass-input"
                />
              </div>

              <div>
                <label className="block text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">Reason Code *</label>
                <select
                  value={adjustment.reasonCode}
                  onChange={(e: any) => setAdjustment({ ...adjustment, reasonCode: e.target.value })}
                  className="w-full px-3 py-2 text-xs glass-input cursor-pointer"
                >
                  <option value="CYCLIC_COUNT_DISCREPANCY">Cyclic Count Discrepancy (Correction)</option>
                  <option value="DAMAGED_GOODS_SCRAP">Damaged Goods Scrap</option>
                  <option value="SUPPLIER_SHORTAGE">Supplier Shortage</option>
                </select>
              </div>

              <div className="pt-4 border-t border-gray-800 flex items-center justify-end gap-3">
                <button
                  type="button"
                  onClick={() => setShowAdjustModal(false)}
                  className="px-4 py-2 rounded-lg bg-transparent hover:bg-gray-800 text-gray-300 text-xs font-medium border border-gray-700 transition cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="px-4 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-xs font-medium border border-indigo-500/20 transition cursor-pointer"
                >
                  {submitting ? 'Applying...' : 'Apply Adjustment'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

// Helper subcomponent for check mark inside success alert
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

export default Catalog;
