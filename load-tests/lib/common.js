import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

// Custom metrics for deeper latency analysis beyond default http_req_duration
export const authLoginDuration = new Trend('custom_auth_login_duration', true);
export const productsListDuration = new Trend('custom_products_list_duration', true);
export const ordersListDuration = new Trend('custom_orders_list_duration', true);
export const analyticsDuration = new Trend('custom_analytics_duration', true);
export const auditLogsDuration = new Trend('custom_audit_logs_duration', true);
export const orderCreateDuration = new Trend('custom_order_create_duration', true);
export const orderStatusDuration = new Trend('custom_order_status_duration', true);
export const stockAdjustDuration = new Trend('custom_stock_adjust_duration', true);
export const writeOperations = new Counter('custom_write_operations');
export const cacheMissReads = new Counter('custom_cache_miss_reads');

export const ADMIN_CREDENTIALS = {
  email: 'admin@pg.com',
  password: 'AdminPassword123',
};

export const STAFF_CREDENTIALS = {
  email: 'staff@pg.com',
  password: 'StaffPassword123',
};

export const ENDPOINTS = {
  HEALTH: 'health',
  AUTH_LOGIN: 'auth_login',
  PRODUCTS_LIST: 'products_list',
  PRODUCT_CREATE: 'product_create',
  STOCK_ADJUST: 'stock_adjust',
  SUPPLIERS_LIST: 'suppliers_list',
  CATEGORIES_LIST: 'categories_list',
  WAREHOUSES_LIST: 'warehouses_list',
  ORDERS_LIST: 'orders_list',
  ORDER_DETAIL: 'order_detail',
  ORDER_CREATE: 'order_create',
  ORDER_STATUS: 'order_status',
  NOTIFICATIONS_LIST: 'notifications_list',
  NOTIFICATION_READ: 'notification_read',
  NOTIFICATIONS_READ_ALL: 'notifications_read_all',
  ANALYTICS_DASHBOARD: 'analytics_dashboard',
  AUDIT_LOGS: 'audit_logs',
};

const METRIC_BY_ENDPOINT = {
  [ENDPOINTS.AUTH_LOGIN]: authLoginDuration,
  [ENDPOINTS.PRODUCTS_LIST]: productsListDuration,
  [ENDPOINTS.ORDERS_LIST]: ordersListDuration,
  [ENDPOINTS.ANALYTICS_DASHBOARD]: analyticsDuration,
  [ENDPOINTS.AUDIT_LOGS]: auditLogsDuration,
  [ENDPOINTS.ORDER_CREATE]: orderCreateDuration,
  [ENDPOINTS.ORDER_STATUS]: orderStatusDuration,
  [ENDPOINTS.STOCK_ADJUST]: stockAdjustDuration,
};

export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function pickRandom(items) {
  if (!items || items.length === 0) return null;
  return items[Math.floor(Math.random() * items.length)];
}

export function getLoadTestScale() {
  const scale = parseFloat(__ENV.LOAD_TEST_SCALE || '1');
  return Number.isFinite(scale) && scale > 0 ? scale : 1;
}

export function scaledVus(baseVus) {
  return Math.max(1, Math.round(baseVus * getLoadTestScale()));
}

export function getSampleSize() {
  const size = parseInt(__ENV.LOAD_TEST_SAMPLE_SIZE || '50', 10);
  return Number.isFinite(size) && size > 0 ? size : 50;
}

function takeSample(items, sampleSize = getSampleSize()) {
  if (!items || items.length === 0) return [];
  return items.slice(0, sampleSize);
}

export function jsonHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };
}

function recordEndpointMetric(endpoint, res) {
  const trend = METRIC_BY_ENDPOINT[endpoint];
  if (trend && res && res.timings) {
    trend.add(res.timings.duration);
  }
}

export function taggedRequest(method, url, body, params, endpoint, role) {
  const tags = {
    endpoint,
    method,
    role: role || 'system',
    name: `${method} ${endpoint}`,
  };

  const requestParams = { ...(params || {}), tags };

  let res;
  switch (method) {
    case 'GET':
      res = http.get(url, requestParams);
      break;
    case 'POST':
      res = http.post(url, body, requestParams);
      break;
    case 'PUT':
      res = http.put(url, body, requestParams);
      break;
    case 'DELETE':
      res = http.del(url, body, requestParams);
      break;
    default:
      throw new Error(`Unsupported HTTP method: ${method}`);
  }

  recordEndpointMetric(endpoint, res);
  return res;
}

export function login(baseUrl, credentials, role) {
  const res = taggedRequest(
    'POST',
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify(credentials),
    { headers: { 'Content-Type': 'application/json' } },
    ENDPOINTS.AUTH_LOGIN,
    role,
  );

  const ok = check(res, {
    [`${role} login responded 200`]: (r) => r.status === 200,
    [`${role} token received`]: (r) => r.json('token') !== null,
  });

  return ok ? res.json('token') : null;
}

export function setupTestData() {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  console.log(`[Setup] Initializing load test data from: ${baseUrl}`);

  const adminToken = login(baseUrl, ADMIN_CREDENTIALS, 'admin');
  const staffToken = login(baseUrl, STAFF_CREDENTIALS, 'staff');

  let categories = [];
  let warehouses = [];
  let products = [];
  let suppliers = [];
  let orders = [];

  if (adminToken) {
    const headers = jsonHeaders(adminToken);

    const catRes = taggedRequest('GET', `${baseUrl}/api/v1/categories`, null, { headers }, ENDPOINTS.CATEGORIES_LIST, 'admin');
    if (catRes.status === 200) categories = catRes.json();

    const whRes = taggedRequest('GET', `${baseUrl}/api/v1/warehouses`, null, { headers }, ENDPOINTS.WAREHOUSES_LIST, 'admin');
    if (whRes.status === 200) warehouses = whRes.json();

    const supRes = taggedRequest('GET', `${baseUrl}/api/v1/suppliers`, null, { headers }, ENDPOINTS.SUPPLIERS_LIST, 'admin');
    if (supRes.status === 200) suppliers = supRes.json();

    const prodRes = taggedRequest('GET', `${baseUrl}/api/v1/inventory/products`, null, { headers }, ENDPOINTS.PRODUCTS_LIST, 'admin');
    if (prodRes.status === 200) products = prodRes.json();

    const orderRes = taggedRequest('GET', `${baseUrl}/api/v1/orders`, null, { headers }, ENDPOINTS.ORDERS_LIST, 'admin');
    if (orderRes.status === 200) orders = orderRes.json();
  }

  const productCount = products.length;
  const orderCount = orders.length;
  const pendingOrders = orders.filter((order) => order.status === 'PENDING_APPROVAL');

  console.log('[Setup] Loaded metadata:');
  console.log(` - Categories: ${categories.length}`);
  console.log(` - Warehouses: ${warehouses.length}`);
  console.log(` - Suppliers: ${suppliers.length}`);
  console.log(` - Products: ${productCount}`);
  console.log(` - Orders: ${orderCount}`);
  console.log(` - Pending approval orders: ${pendingOrders.length}`);

  if (productCount > 500 || orderCount > 500) {
    console.warn(
      `[Setup] Large dataset detected (products=${productCount}, orders=${orderCount}). ` +
      'Only a small sample is passed to VUs to limit memory use. ' +
      'List endpoints are still hit at full size each iteration.',
    );
  }

  return {
    adminToken,
    staffToken,
    categories,
    warehouses,
    products: takeSample(products),
    suppliers,
    orders: takeSample(orders),
    pendingOrders: takeSample(pendingOrders, 20),
    productCount,
    orderCount,
    baseUrl,
  };
}

export function checkHealth(baseUrl) {
  const res = taggedRequest('GET', `${baseUrl}/api/health`, null, {}, ENDPOINTS.HEALTH, 'system');
  check(res, {
    'Health check responded 200': (r) => r.status === 200,
    'Database is healthy': (r) => r.json('database') === 'HEALTHY',
  });
  return res;
}

export function browseProducts(baseUrl, token, role) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/inventory/products`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.PRODUCTS_LIST,
    role,
  );
  check(res, { [`${role} - GET products 200`]: (r) => r.status === 200 });
  return res;
}

export function listOrders(baseUrl, token, role) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/orders`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.ORDERS_LIST,
    role,
  );
  check(res, { [`${role} - GET orders 200`]: (r) => r.status === 200 });
  return res;
}

export function getOrderDetail(baseUrl, token, role, orderId) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/orders/${orderId}`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.ORDER_DETAIL,
    role,
  );
  check(res, { [`${role} - GET order detail 200`]: (r) => r.status === 200 });
  return res;
}

export function listSuppliers(baseUrl, token, role) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/suppliers`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.SUPPLIERS_LIST,
    role,
  );
  check(res, { [`${role} - GET suppliers 200`]: (r) => r.status === 200 });
  return res;
}

export function listCategories(baseUrl, token, role) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/categories`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.CATEGORIES_LIST,
    role,
  );
  check(res, { [`${role} - GET categories 200`]: (r) => r.status === 200 });
  return res;
}

export function listWarehouses(baseUrl, token, role) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/warehouses`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.WAREHOUSES_LIST,
    role,
  );
  check(res, { [`${role} - GET warehouses 200`]: (r) => r.status === 200 });
  return res;
}

export function listNotifications(baseUrl, token, role) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/notifications`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.NOTIFICATIONS_LIST,
    role,
  );
  check(res, { [`${role} - GET notifications 200`]: (r) => r.status === 200 });
  return res;
}

export function markNotificationRead(baseUrl, token, role, notificationId) {
  const res = taggedRequest(
    'PUT',
    `${baseUrl}/api/v1/notifications/${notificationId}/read`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.NOTIFICATION_READ,
    role,
  );
  check(res, { [`${role} - PUT notification read 200`]: (r) => r.status === 200 });
  return res;
}

export function markAllNotificationsRead(baseUrl, token, role) {
  const res = taggedRequest(
    'PUT',
    `${baseUrl}/api/v1/notifications/read-all`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.NOTIFICATIONS_READ_ALL,
    role,
  );
  check(res, { [`${role} - PUT notifications read-all 200`]: (r) => r.status === 200 });
  return res;
}

export function getAnalyticsDashboard(baseUrl, token) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/analytics/dashboard`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.ANALYTICS_DASHBOARD,
    'admin',
  );
  check(res, { 'Admin - GET analytics 200': (r) => r.status === 200 });
  return res;
}

export function getAuditLogs(baseUrl, token, page = 0, size = 50) {
  const res = taggedRequest(
    'GET',
    `${baseUrl}/api/v1/audit-logs?page=${page}&size=${size}`,
    null,
    { headers: jsonHeaders(token) },
    ENDPOINTS.AUDIT_LOGS,
    'admin',
  );
  check(res, { 'Admin - GET audit logs 200': (r) => r.status === 200 });
  return res;
}

export function createProduct(baseUrl, token, categories, warehouses, skuPrefix) {
  const category = pickRandom(categories);
  const warehouse = pickRandom(warehouses);
  if (!category || !warehouse) return null;

  const uniqueSku = `${skuPrefix}-${__VU}-${__ITER}-${Date.now()}`;
  const payload = JSON.stringify({
    sku: uniqueSku,
    name: `Load Test Product ${__VU}-${__ITER}`,
    description: 'Dynamically generated product during load testing',
    categoryId: category.id,
    unitPrice: parseFloat((Math.random() * 50 + 5).toFixed(2)),
    reorderLevel: randomInt(5, 20),
    warehouseId: warehouse.id,
    isActive: true,
  });

  const res = taggedRequest(
    'POST',
    `${baseUrl}/api/v1/inventory/products`,
    payload,
    { headers: jsonHeaders(token) },
    ENDPOINTS.PRODUCT_CREATE,
    'admin',
  );

  const ok = check(res, { 'Admin - POST create product 201': (r) => r.status === 201 });
  if (ok) {
    writeOperations.add(1);
    cacheMissReads.add(1);
    return res.json('id');
  }
  return null;
}

export function adjustStock(baseUrl, token, productId) {
  const reasonCodes = ['CYCLIC_COUNT_DISCREPANCY', 'DAMAGED_GOODS_SCRAP', 'SUPPLIER_SHORTAGE'];
  const payload = JSON.stringify({
    quantityAdjustment: randomInt(1, 20),
    reasonCode: pickRandom(reasonCodes),
  });

  const res = taggedRequest(
    'POST',
    `${baseUrl}/api/v1/inventory/products/${productId}/adjust`,
    payload,
    { headers: jsonHeaders(token) },
    ENDPOINTS.STOCK_ADJUST,
    'admin',
  );

  const ok = check(res, { 'Admin - POST stock adjust 200': (r) => r.status === 200 });
  if (ok) {
    writeOperations.add(1);
    cacheMissReads.add(1);
  }
  return ok;
}

export function createOrder(baseUrl, token, role, suppliers, warehouses, products) {
  const supplier = pickRandom(suppliers);
  const warehouse = pickRandom(warehouses);
  const product = pickRandom(products);
  if (!supplier || !warehouse || !product) return null;

  const payload = JSON.stringify({
    supplierId: supplier.id,
    warehouseId: warehouse.id,
    items: [{ productId: product.id, quantity: randomInt(5, 50) }],
  });

  const res = taggedRequest(
    'POST',
    `${baseUrl}/api/v1/orders`,
    payload,
    { headers: jsonHeaders(token) },
    ENDPOINTS.ORDER_CREATE,
    role,
  );

  const ok = check(res, { [`${role} - POST create order 201`]: (r) => r.status === 201 });
  if (ok) {
    writeOperations.add(1);
    cacheMissReads.add(1);
    return res.json();
  }
  return null;
}

export function updateOrderStatus(baseUrl, token, role, orderId, status) {
  const payload = JSON.stringify({ status });
  const res = taggedRequest(
    'PUT',
    `${baseUrl}/api/v1/orders/${orderId}/status`,
    payload,
    { headers: jsonHeaders(token) },
    ENDPOINTS.ORDER_STATUS,
    role,
  );

  const ok = check(res, {
    [`${role} - PUT order status ${status} 200`]: (r) => r.status === 200,
  });
  if (ok) {
    writeOperations.add(1);
    cacheMissReads.add(1);
  }
  return ok ? res.json() : null;
}

export function advanceOrderThroughLifecycle(baseUrl, staffToken, adminToken, suppliers, warehouses, products) {
  const created = createOrder(baseUrl, staffToken, 'staff', suppliers, warehouses, products);
  if (!created) return;

  updateOrderStatus(baseUrl, staffToken, 'staff', created.id, 'PENDING_APPROVAL');

  if (adminToken) {
    updateOrderStatus(baseUrl, adminToken, 'admin', created.id, 'APPROVED');
    updateOrderStatus(baseUrl, adminToken, 'admin', created.id, 'SHIPPED');
    updateOrderStatus(baseUrl, adminToken, 'admin', created.id, 'DELIVERED');
  }
}

export function runStaffWorkflow(data, options = {}) {
  const { staffToken, suppliers, warehouses, products, orders, baseUrl } = data;
  const { includeWrites = true, thinkTime = [1, 3] } = options;

  if (!staffToken) {
    console.warn(`[VU ${__VU}] Staff token missing, skipping iteration.`);
    sleep(1);
    return;
  }

  group('Staff - Catalog & Orders', () => {
    browseProducts(baseUrl, staffToken, 'staff');
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    listSuppliers(baseUrl, staffToken, 'staff');
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    listOrders(baseUrl, staffToken, 'staff');
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    const order = pickRandom(orders);
    if (order) {
      getOrderDetail(baseUrl, staffToken, 'staff', order.id);
      sleep(randomInt(thinkTime[0], thinkTime[1]));
    }
  });

  group('Staff - Notifications', () => {
    const notifyRes = listNotifications(baseUrl, staffToken, 'staff');
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    if (notifyRes.status === 200) {
      const notifications = notifyRes.json();
      const unread = notifications.filter((n) => !n.isRead);
      if (unread.length > 0 && Math.random() < 0.3) {
        markNotificationRead(baseUrl, staffToken, 'staff', pickRandom(unread).id);
      } else if (Math.random() < 0.05) {
        markAllNotificationsRead(baseUrl, staffToken, 'staff');
      }
    }
  });

  if (includeWrites) {
    group('Staff - Write Operations', () => {
      if (Math.random() < 0.15 && suppliers.length && warehouses.length && products.length) {
        const created = createOrder(baseUrl, staffToken, 'staff', suppliers, warehouses, products);
        if (created && Math.random() < 0.5) {
          updateOrderStatus(baseUrl, staffToken, 'staff', created.id, 'PENDING_APPROVAL');
        }
      }

      if (Math.random() < 0.05 && suppliers.length && warehouses.length && products.length) {
        advanceOrderThroughLifecycle(baseUrl, staffToken, data.adminToken, suppliers, warehouses, products);
      }
    });
  }
}

export function runAdminWorkflow(data, options = {}) {
  const { adminToken, categories, warehouses, products, pendingOrders, baseUrl } = data;
  const { includeWrites = true, thinkTime = [2, 4], skuPrefix = 'SKU-LOAD' } = options;

  if (!adminToken) {
    console.warn(`[VU ${__VU}] Admin token missing, skipping iteration.`);
    sleep(1);
    return;
  }

  group('Admin - Analytics & Audit', () => {
    getAnalyticsDashboard(baseUrl, adminToken);
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    getAuditLogs(baseUrl, adminToken, randomInt(0, 2), randomInt(25, 100));
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    listCategories(baseUrl, adminToken, 'admin');
    sleep(randomInt(thinkTime[0], thinkTime[1]));

    listWarehouses(baseUrl, adminToken, 'admin');
    sleep(randomInt(thinkTime[0], thinkTime[1]));
  });

  group('Admin - Orders Overview', () => {
    listOrders(baseUrl, adminToken, 'admin');
    if (pendingOrders.length > 0 && Math.random() < 0.2) {
      updateOrderStatus(baseUrl, adminToken, 'admin', pickRandom(pendingOrders).id, 'APPROVED');
    }
  });

  if (includeWrites) {
    group('Admin - Inventory Writes', () => {
      let newProductId = null;
      if (Math.random() < 0.1 && categories.length && warehouses.length) {
        newProductId = createProduct(baseUrl, adminToken, categories, warehouses, skuPrefix);
      }

      if (Math.random() < 0.2 && products.length) {
        const targetId = newProductId || pickRandom(products).id;
        adjustStock(baseUrl, adminToken, targetId);
      }
    });
  }
}

export function runMixedUserIteration(data, options = {}) {
  if (Math.random() < 0.02) {
    checkHealth(data.baseUrl);
  }

  const isStaff = (__VU % 5) !== 0;
  if (isStaff) {
    runStaffWorkflow(data, options);
  } else {
    runAdminWorkflow(data, options);
  }

  sleep(randomInt(1, 2));
}

export function buildEndpointThresholds(p95Ms = 500) {
  const endpoints = Object.values(ENDPOINTS);
  const thresholds = {
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${p95Ms}`],
    custom_write_operations: ['count>=0'],
  };

  endpoints.forEach((endpoint) => {
    thresholds[`http_req_duration{endpoint:${endpoint}}`] = [`p(95)<${p95Ms}`];
  });

  return thresholds;
}

function extractEndpointBreakdown(data) {
  const metrics = data.metrics || {};
  const breakdown = [];

  Object.entries(metrics).forEach(([name, metric]) => {
    const match = name.match(/^http_req_duration\{endpoint:([^}]+)\}$/);
    if (!match || !metric.values) return;

    breakdown.push({
      endpoint: match[1],
      avg_ms: metric.values.avg,
      med_ms: metric.values.med,
      p90_ms: metric.values['p(90)'],
      p95_ms: metric.values['p(95)'],
      p99_ms: metric.values['p(99)'],
      max_ms: metric.values.max,
      count: metric.values.count,
    });
  });

  breakdown.sort((a, b) => (b.p95_ms || 0) - (a.p95_ms || 0));
  return breakdown;
}

export function createSummaryHandler(label) {
  return function handleSummary(data) {
    const endpointBreakdown = extractEndpointBreakdown(data);
    const slowest = endpointBreakdown.slice(0, 5);

    console.log(`\n[${label}] Top 5 slowest endpoints by p95:`);
    slowest.forEach((entry, index) => {
      console.log(
        ` ${index + 1}. ${entry.endpoint}: p95=${entry.p95_ms?.toFixed(1)}ms avg=${entry.avg_ms?.toFixed(1)}ms count=${entry.count}`,
      );
    });

    const outputs = {
      stdout: textSummary(data, { indent: ' ', enableColors: true }),
      'results/endpoints-breakdown.json': JSON.stringify(
        { label, generatedAt: new Date().toISOString(), endpoints: endpointBreakdown },
        null,
        2,
      ),
    };

    if (__ENV.K6_FULL_SUMMARY === '1') {
      outputs['results/full-summary.json'] = JSON.stringify(data, null, 2);
    }

    return outputs;
  };
}
