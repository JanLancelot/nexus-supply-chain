// Select one valid single-warehouse order from the API's setup snapshot.
export function selectOrderReferences(suppliers, warehouses, products, choose) {
  const warehouseIds = new Set((warehouses || []).filter((warehouse) => warehouse?.id).map((warehouse) => warehouse.id));
  const supplier = choose((suppliers || []).filter((candidate) => candidate?.id && candidate.active === true));
  const product = choose((products || []).filter((candidate) =>
    candidate?.id && candidate.isActive === true && warehouseIds.has(candidate.warehouseId)));
  if (!supplier || !product) return null;
  return { supplierId: supplier.id, warehouseId: product.warehouseId, productId: product.id };
}
