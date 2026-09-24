import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import ProductPicker from '../components/ProductPicker';
import { createCategory, createWarehouse, getCategories, getProduct, getWarehouses, type Category, type Warehouse } from '../services/products';
import { createSupplier, getSuppliers, setSupplierProducts, type Supplier } from '../services/suppliers';
import { getErrorMessage } from '../services/errors';
import type { Product } from '../types';

const inputStyle = 'w-full mt-1 rounded-lg glass-input p-2.5 text-sm';
const buttonStyle = 'rounded-lg border border-indigo-500/30 bg-indigo-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40';
const panelStyle = 'glass-panel rounded-2xl border border-gray-800 p-5 space-y-4';

export default function ReferenceData() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [category, setCategory] = useState({ name: '', description: '' });
  const [warehouse, setWarehouse] = useState({ name: '', location: '' });
  const [supplier, setSupplier] = useState({ name: '', contactPerson: '', email: '', phone: '', address: '', leadTimeDays: 3 });
  const [sourceSupplier, setSourceSupplier] = useState<Supplier | null>(null);
  const [sourceProducts, setSourceProducts] = useState<Product[]>([]);

  const load = useCallback(() => {
    return Promise.all([getCategories(), getWarehouses(), getSuppliers()]).then(([categoryRows, warehouseRows, supplierRows]) => {
      setCategories(categoryRows);
      setWarehouses(warehouseRows);
      setSuppliers(supplierRows);
      setError(null);
    }).catch(err => {
      setError(getErrorMessage(err, 'Unable to load reference data. Please retry.'));
    }).finally(() => {
      setLoading(false);
    });
  }, []);
  useEffect(() => { void load(); }, [load]);

  const save = async (event: FormEvent, action: () => Promise<void>) => {
    event.preventDefault();
    setError(null);
    setSuccess(null);
    setSaving(true);
    try { await action(); }
    catch (err) { setError(getErrorMessage(err, 'Unable to save reference data. Please try again.')); }
    finally { setSaving(false); }
  };

  const editSources = async (row: Supplier) => {
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      const assigned = await Promise.all(row.productIds.map(getProduct));
      setSourceProducts(assigned);
      setSourceSupplier(row);
    } catch (err) { setError(getErrorMessage(err, 'Unable to load supplier products. Please retry.')); }
    finally { setSaving(false); }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-white">Set up your supply chain</h2>
          <p className="mt-2 text-sm text-gray-400">Create categories, warehouses and suppliers, then add products in the <Link className="text-indigo-400 underline" to="/catalog">catalog</Link>.</p>
          <p className="mt-1 text-sm text-gray-400">Automatic replenishment uses assigned suppliers. Low-stock products without an assigned supplier need a manual order.</p>
        </div>
        <button type="button" className={buttonStyle} disabled={loading || saving} onClick={() => { setLoading(true); void load(); }}>Refresh reference data</button>
      </div>
      {error && <p role="alert" className="rounded-lg border border-red-500/25 bg-red-950/40 p-4 text-sm text-red-300">{error}</p>}
      {success && <p role="status" className="rounded-lg border border-emerald-500/25 p-4 text-sm text-emerald-300">{success}</p>}
      {loading ? <p>Loading reference data...</p> : <>
        <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
          <section className={panelStyle}>
            <h3 className="font-bold text-white">Categories</h3>
            <ul className="max-h-40 overflow-y-auto space-y-2 text-sm text-gray-300">{categories.map(row => <li key={row.id}>{row.name}{row.description && <span className="block text-xs text-gray-400">{row.description}</span>}</li>)}</ul>
            {!categories.length && <p className="text-sm text-gray-500">No categories yet.</p>}
            <form aria-label="Create category" className="space-y-3" onSubmit={event => void save(event, async () => {
              const created = await createCategory({ name: category.name.trim(), description: category.description.trim() });
              setCategories(rows => [...rows, created]); setCategory({ name: '', description: '' }); setSuccess(`Category ${created.name} created.`);
            })}>
              <label className="block text-xs text-gray-400">Category name<input className={inputStyle} required maxLength={255} value={category.name} onChange={event => setCategory({ ...category, name: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Description<input className={inputStyle} maxLength={255} value={category.description} onChange={event => setCategory({ ...category, description: event.target.value })} /></label>
              <button className={buttonStyle} disabled={saving}>Create category</button>
            </form>
          </section>
          <section className={panelStyle}>
            <h3 className="font-bold text-white">Warehouses</h3>
            <ul className="max-h-40 overflow-y-auto space-y-2 text-sm text-gray-300">{warehouses.map(row => <li key={row.id}>{row.name}{row.location && <span className="block text-xs text-gray-400">{row.location}</span>}</li>)}</ul>
            {!warehouses.length && <p className="text-sm text-gray-500">No warehouses yet.</p>}
            <form aria-label="Create warehouse" className="space-y-3" onSubmit={event => void save(event, async () => {
              const created = await createWarehouse({ name: warehouse.name.trim(), location: warehouse.location.trim() });
              setWarehouses(rows => [...rows, created]); setWarehouse({ name: '', location: '' }); setSuccess(`Warehouse ${created.name} created.`);
            })}>
              <label className="block text-xs text-gray-400">Warehouse name<input className={inputStyle} required maxLength={255} value={warehouse.name} onChange={event => setWarehouse({ ...warehouse, name: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Location<input className={inputStyle} maxLength={255} value={warehouse.location} onChange={event => setWarehouse({ ...warehouse, location: event.target.value })} /></label>
              <button className={buttonStyle} disabled={saving}>Create warehouse</button>
            </form>
          </section>
          <section className={panelStyle}>
            <h3 className="font-bold text-white">Suppliers</h3>
            <form aria-label="Create supplier" className="space-y-3" onSubmit={event => void save(event, async () => {
              const created = await createSupplier({ ...supplier, name: supplier.name.trim(), isActive: true, productIds: [] });
              setSuppliers(rows => [...rows, created]); setSupplier({ name: '', contactPerson: '', email: '', phone: '', address: '', leadTimeDays: 3 }); setSuccess(`Supplier ${created.name} created.`);
            })}>
              <label className="block text-xs text-gray-400">Supplier name<input className={inputStyle} required maxLength={255} value={supplier.name} onChange={event => setSupplier({ ...supplier, name: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Contact person<input className={inputStyle} maxLength={255} value={supplier.contactPerson} onChange={event => setSupplier({ ...supplier, contactPerson: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Email<input className={inputStyle} type="email" maxLength={255} value={supplier.email} onChange={event => setSupplier({ ...supplier, email: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Phone<input className={inputStyle} maxLength={50} value={supplier.phone} onChange={event => setSupplier({ ...supplier, phone: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Address<input className={inputStyle} maxLength={255} value={supplier.address} onChange={event => setSupplier({ ...supplier, address: event.target.value })} /></label>
              <label className="block text-xs text-gray-400">Lead time (days)<input className={inputStyle} type="number" min={0} max={3650} required value={supplier.leadTimeDays} onChange={event => setSupplier({ ...supplier, leadTimeDays: Number(event.target.value) })} /></label>
              <button className={buttonStyle} disabled={saving}>Create supplier</button>
            </form>
          </section>
        </div>
        <section className={panelStyle}>
          <h3 className="font-bold text-white">Supplier product sourcing</h3>
          {!suppliers.length && <p className="text-sm text-gray-500">Create a supplier to assign products.</p>}
          <ul className="space-y-3">{suppliers.map(row => <li key={row.id} className="flex flex-wrap justify-between items-center gap-3 border-b border-gray-800 pb-3">
            <div><span className="text-sm font-semibold text-gray-200">{row.name}</span><p className="text-xs text-gray-400">{row.active ? 'Active' : 'Inactive'} · {row.productIds.length} assigned products · {row.leadTimeDays} day lead time</p></div>
            <button className="text-sm text-indigo-400 disabled:opacity-40" disabled={saving} onClick={() => void editSources(row)}>Manage products for {row.name}</button>
          </li>)}</ul>
          {sourceSupplier && <form aria-label="Supplier product sourcing" className="space-y-4 max-w-xl" onSubmit={event => void save(event, async () => {
            const productIds = sourceProducts.map(product => product.id);
            await setSupplierProducts(sourceSupplier.id, productIds);
            setSuppliers(rows => rows.map(row => row.id === sourceSupplier.id ? { ...row, productIds } : row));
            setSuccess(`Products assigned to ${sourceSupplier.name}.`); setSourceSupplier(null);
          })}>
            <h4 className="font-semibold text-gray-200">Products supplied by {sourceSupplier.name}</h4>
            <ProductPicker key={sourceSupplier.id} onChange={product => { if (product) setSourceProducts(rows => rows.some(row => row.id === product.id) ? rows : [...rows, product]); }} />
            <ul className="space-y-2">{sourceProducts.map(product => <li key={product.id} className="flex justify-between gap-3 text-sm text-gray-300"><span>{product.sku} — {product.name}{!product.isActive && ' (Inactive)'}</span><button type="button" className="text-red-400" onClick={() => setSourceProducts(rows => rows.filter(row => row.id !== product.id))}>Remove {product.sku}</button></li>)}</ul>
            <div className="flex gap-3"><button className={buttonStyle} disabled={saving}>Save supplier products</button><button type="button" disabled={saving} onClick={() => setSourceSupplier(null)} className="text-sm text-gray-400">Cancel</button></div>
          </form>}
        </section>
      </>}
    </div>
  );
}
