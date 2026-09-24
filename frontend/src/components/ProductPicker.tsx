import { useCallback, useEffect, useRef, useState } from 'react';
import { getProducts } from '../services/products';
import { getErrorMessage } from '../services/errors';
import type { Product } from '../types';

interface Props {
  value?: Product;
  onChange: (product?: Product) => void;
  warehouseId?: string;
  disabled?: boolean;
  required?: boolean;
}

/** Searches the complete catalog while retaining a selected product across result pages. */
export default function ProductPicker({ value, onChange, warehouseId, disabled = false, required = false }: Props) {
  const [products, setProducts] = useState<Product[]>([]);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const load = useCallback((nextPage: number, nextQuery: string) => {
    if (disabled) return;
    const request = ++requestId.current;
    return getProducts(nextPage, 50, { search: nextQuery, warehouseId, active: true }).then(result => {
      if (request !== requestId.current) return;
      setProducts(result.content.filter(product => product.isActive && (!warehouseId || product.warehouseId === warehouseId)));
      setPage(nextPage);
      setQuery(nextQuery);
      setHasNext(result.hasNext);
      setError(null);
    }).catch(err => {
      if (request === requestId.current) setError(getErrorMessage(err, 'Unable to load products. Search again to retry.'));
    }).finally(() => {
      if (request === requestId.current) setLoading(false);
    });
  }, [warehouseId, disabled]);

  useEffect(() => {
    void load(0, '');
    return () => { requestId.current += 1; };
  }, [load]);

  const options = value && !products.some(product => product.id === value.id) ? [value, ...products] : products;
  return (
    <div className="space-y-2 min-w-0">
      <div className="flex gap-2">
        <input aria-label="Search products by SKU or name" placeholder="Search all products by SKU or name"
          value={search} onChange={event => setSearch(event.target.value)} disabled={disabled}
          onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); setLoading(true); void load(0, search); } }}
          className="w-full min-w-0 px-3 py-2 text-xs glass-input" />
        <button type="button" disabled={disabled || loading} onClick={() => { setLoading(true); void load(0, search); }}
          className="px-3 py-2 text-xs border border-gray-700 rounded-lg disabled:opacity-40">Search</button>
      </div>
      {error && <p role="alert" className="text-xs text-red-300">{error}</p>}
      <select aria-label="Select SKU Product" value={value?.id ?? ''} required={required}
        disabled={disabled || loading} onChange={event => onChange(options.find(product => product.id === event.target.value))}
        className="w-full px-3 py-2 text-xs glass-input cursor-pointer disabled:opacity-50">
        <option value="">Select SKU Product</option>
        {options.map(product => <option key={product.id} value={product.id}>{product.sku} - {product.name} (${product.unitPrice.toFixed(2)})</option>)}
      </select>
      {disabled ? <p className="text-xs text-gray-400">Choose a destination warehouse first.</p> : (
        <div className="flex items-center justify-between gap-2 text-[10px] text-gray-400">
          <span>{loading ? 'Loading products...' : `Product results page ${page + 1}`}</span>
          <div className="flex gap-2">
            <button type="button" disabled={loading || page === 0} onClick={() => { setLoading(true); void load(page - 1, query); }}
              className="disabled:opacity-40">Previous products</button>
            <button type="button" disabled={loading || !hasNext} onClick={() => { setLoading(true); void load(page + 1, query); }}
              className="disabled:opacity-40">Next products</button>
          </div>
        </div>
      )}
      {!disabled && !loading && !products.length && !error && <p className="text-xs text-gray-400">No active products match this search.</p>}
    </div>
  );
}
