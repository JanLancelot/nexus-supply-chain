import http from 'k6/http';
import { check, sleep } from 'k6';

// Define the stress testing options reaching 1000 VUs
export const options = {
  stages: [
    { duration: '30s', target: 100 },  // Ramp-up to 100 users
    { duration: '1m', target: 500 },   // Ramp-up to 500 users
    { duration: '1m', target: 1000 },  // Ramp-up to 1000 users
    { duration: '2m', target: 1000 },  // Sustain 1000 users
    { duration: '30s', target: 0 },    // Ramp-down to 0 users
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],    // HTTP error rate must be less than 5% under stress
    http_req_duration: ['p(95)<1000'], // 95% of requests must complete under 1s under stress
  },
};

// Setup phase: Authenticate users and gather basic configuration data once
export function setup() {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  console.log(`[Setup] Starting stress test initialization targeting: ${baseUrl}`);

  // 1. Authenticate admin user
  const adminLoginRes = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: 'admin@pg.com',
    password: 'AdminPassword123'
  }), {
    headers: { 'Content-Type': 'application/json' }
  });

  const adminOk = check(adminLoginRes, {
    'Admin login responded 200': (r) => r.status === 200,
    'Admin token received': (r) => r.json('token') !== null,
  });

  const adminToken = adminOk ? adminLoginRes.json('token') : null;

  // 2. Authenticate staff user
  const staffLoginRes = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
    email: 'staff@pg.com',
    password: 'StaffPassword123'
  }), {
    headers: { 'Content-Type': 'application/json' }
  });

  const staffOk = check(staffLoginRes, {
    'Staff login responded 200': (r) => r.status === 200,
    'Staff token received': (r) => r.json('token') !== null,
  });

  const staffToken = staffOk ? staffLoginRes.json('token') : null;

  // 3. Query base entities needed to generate realistic writes/updates
  let categories = [];
  let warehouses = [];
  let products = [];
  let suppliers = [];

  if (adminToken) {
    const authHeaders = {
      'Authorization': `Bearer ${adminToken}`,
      'Content-Type': 'application/json',
    };

    // Get categories
    const catRes = http.get(`${baseUrl}/api/v1/categories`, { headers: authHeaders });
    if (catRes.status === 200) categories = catRes.json();

    // Get warehouses
    const whRes = http.get(`${baseUrl}/api/v1/warehouses`, { headers: authHeaders });
    if (whRes.status === 200) warehouses = whRes.json();

    // Get suppliers
    const supRes = http.get(`${baseUrl}/api/v1/suppliers`, { headers: authHeaders });
    if (supRes.status === 200) suppliers = supRes.json();

    // Get existing products
    const prodRes = http.get(`${baseUrl}/api/v1/inventory/products`, { headers: authHeaders });
    if (prodRes.status === 200) products = prodRes.json();
  }

  console.log(`[Setup] Successfully loaded initial metadata from backend:`);
  console.log(` - Categories: ${categories.length}`);
  console.log(` - Warehouses: ${warehouses.length}`);
  console.log(` - Suppliers: ${suppliers.length}`);
  console.log(` - Products: ${products.length}`);

  return {
    adminToken,
    staffToken,
    categories,
    warehouses,
    products,
    suppliers,
    baseUrl,
  };
}

// Generate random number helper
function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

// Main virtual user execution loop
export default function (data) {
  const { adminToken, staffToken, categories, warehouses, products, suppliers, baseUrl } = data;

  // Assign role based on Virtual User ID to maintain an 80/20 Staff/Admin workload split
  const isStaff = (__VU % 5) !== 0;

  if (isStaff) {
    // STAFF WORKFLOW (80% VU allocation)
    if (!staffToken) {
      console.warn(`[VU ${__VU}] Staff token missing, skipping iteration.`);
      sleep(1);
      return;
    }

    const headers = {
      'Authorization': `Bearer ${staffToken}`,
      'Content-Type': 'application/json',
    };

    // Step 1: Browse catalog
    const browseRes = http.get(`${baseUrl}/api/v1/inventory/products`, { headers });
    check(browseRes, {
      'Staff - GET Products responded 200': (r) => r.status === 200,
    });
    sleep(randomInt(1, 3));

    // Step 2: Fetch suppliers list
    const supplierRes = http.get(`${baseUrl}/api/v1/suppliers`, { headers });
    check(supplierRes, {
      'Staff - GET Suppliers responded 200': (r) => r.status === 200,
    });
    sleep(randomInt(1, 3));

    // Step 3: Check notifications
    const notifyRes = http.get(`${baseUrl}/api/v1/notifications`, { headers });
    check(notifyRes, {
      'Staff - GET Notifications responded 200': (r) => r.status === 200,
    });
    sleep(randomInt(1, 3));

    // Step 4: Create a purchase order (10% chance per iteration)
    if (Math.random() < 0.1 && suppliers.length && warehouses.length && products.length) {
      const randomSupplier = suppliers[Math.floor(Math.random() * suppliers.length)];
      const randomWarehouse = warehouses[Math.floor(Math.random() * warehouses.length)];
      const randomProduct = products[Math.floor(Math.random() * products.length)];

      const orderPayload = JSON.stringify({
        supplierId: randomSupplier.id,
        warehouseId: randomWarehouse.id,
        items: [
          {
            productId: randomProduct.id,
            quantity: randomInt(5, 50),
          }
        ]
      });

      const orderRes = http.post(`${baseUrl}/api/v1/orders`, orderPayload, { headers });
      check(orderRes, {
        'Staff - POST Create Order responded 201': (r) => r.status === 201,
      });
    }

  } else {
    // ADMIN WORKFLOW (20% VU allocation)
    if (!adminToken) {
      console.warn(`[VU ${__VU}] Admin token missing, skipping iteration.`);
      sleep(1);
      return;
    }

    const headers = {
      'Authorization': `Bearer ${adminToken}`,
      'Content-Type': 'application/json',
    };

    // Step 1: Monitor Analytics Dashboard
    const analyticsRes = http.get(`${baseUrl}/api/v1/analytics/dashboard`, { headers });
    check(analyticsRes, {
      'Admin - GET Analytics Dashboard responded 200': (r) => r.status === 200,
    });
    sleep(randomInt(2, 4));

    // Step 2: Browse audit logs
    const auditRes = http.get(`${baseUrl}/api/v1/audit-logs`, { headers });
    check(auditRes, {
      'Admin - GET Audit Logs responded 200': (r) => r.status === 200,
    });
    sleep(randomInt(2, 4));

    // Step 3: Create a new product (10% chance per iteration)
    let newProductId = null;
    if (Math.random() < 0.1 && categories.length && warehouses.length) {
      const randomCat = categories[Math.floor(Math.random() * categories.length)];
      const randomWH = warehouses[Math.floor(Math.random() * warehouses.length)];
      const uniqueSku = `SKU-STRESS-${__VU}-${__ITER}-${Date.now()}`;

      const productPayload = JSON.stringify({
        sku: uniqueSku,
        name: `Stress Test Product ${__VU}-${__ITER}`,
        description: 'Dynamically generated product during stress testing',
        categoryId: randomCat.id,
        unitPrice: parseFloat((Math.random() * 50 + 5).toFixed(2)),
        reorderLevel: randomInt(5, 20),
        warehouseId: randomWH.id,
        isActive: true,
      });

      const createRes = http.post(`${baseUrl}/api/v1/inventory/products`, productPayload, { headers });
      const createdOk = check(createRes, {
        'Admin - POST Create Product responded 201': (r) => r.status === 201,
      });

      if (createdOk) {
        newProductId = createRes.json('id');
      }
    }

    // Step 4: Adjust product stock (20% chance per iteration)
    if (Math.random() < 0.2 && products.length) {
      const targetProduct = newProductId ? { id: newProductId } : products[Math.floor(Math.random() * products.length)];
      const reasonCodes = ['CYCLIC_COUNT_DISCREPANCY', 'DAMAGED_GOODS_SCRAP', 'SUPPLIER_SHORTAGE'];
      const randomReason = reasonCodes[Math.floor(Math.random() * reasonCodes.length)];

      const adjustPayload = JSON.stringify({
        quantityAdjustment: randomInt(1, 20),
        reasonCode: randomReason,
      });

      const adjustRes = http.post(`${baseUrl}/api/v1/inventory/products/${targetProduct.id}/adjust`, adjustPayload, { headers });
      check(adjustRes, {
        'Admin - POST Stock Adjustment responded 200': (r) => r.status === 200,
      });
    }
  }

  // General think time between iterations
  sleep(randomInt(1, 2));
}
