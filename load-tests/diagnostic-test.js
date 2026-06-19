import { sleep } from 'k6';
import {
  setupTestData,
  login,
  checkHealth,
  browseProducts,
  listOrders,
  getOrderDetail,
  listSuppliers,
  listNotifications,
  getAnalyticsDashboard,
  getAuditLogs,
  createOrder,
  advanceOrderThroughLifecycle,
  adjustStock,
  createProduct,
  runMixedUserIteration,
  STAFF_CREDENTIALS,
  buildEndpointThresholds,
  createSummaryHandler,
  scaledVus,
  randomInt,
  pickRandom,
} from './lib/common.js';

const PHASE_DURATION = __ENV.DIAG_PHASE_DURATION || '40s';
const PHASE_SECONDS = parseInt(PHASE_DURATION, 10) || 40;

function phaseStart(index) {
  return `${index * PHASE_SECONDS}s`;
}

// Scenarios run one at a time to keep memory use low (~15 VUs max vs 213 parallel).
function buildSequentialScenarios() {
  const scenarios = {};
  let index = 0;

  const addConstantPhase = (name, exec, baseVus) => {
    scenarios[name] = {
      executor: 'constant-vus',
      vus: scaledVus(baseVus),
      duration: PHASE_DURATION,
      startTime: phaseStart(index),
      exec,
      tags: { scenario: name },
    };
    index += 1;
  };

  addConstantPhase('read_catalog', 'readCatalog', 12);
  addConstantPhase('read_orders', 'readOrders', 10);
  addConstantPhase('read_analytics', 'readAnalytics', 8);
  addConstantPhase('read_audit_logs', 'readAuditLogs', 6);
  addConstantPhase('write_inventory', 'writeInventory', 5);
  addConstantPhase('write_orders', 'writeOrders', 6);
  addConstantPhase('order_lifecycle', 'orderLifecycle', 4);

  scenarios.auth_stress = {
    executor: 'constant-arrival-rate',
    rate: scaledVus(2),
    timeUnit: '1s',
    duration: PHASE_DURATION,
    preAllocatedVUs: scaledVus(3),
    maxVUs: scaledVus(8),
    startTime: phaseStart(index),
    exec: 'authStress',
    tags: { scenario: 'auth_stress' },
  };
  index += 1;

  addConstantPhase('cache_pressure', 'cachePressure', 8);

  scenarios.mixed_realistic = {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '10s', target: scaledVus(10) },
      { duration: '20s', target: scaledVus(10) },
      { duration: '10s', target: 0 },
    ],
    startTime: phaseStart(index),
    exec: 'mixedRealistic',
    tags: { scenario: 'mixed_realistic' },
  };

  return scenarios;
}

export const options = {
  scenarios: buildSequentialScenarios(),
  thresholds: {
    ...buildEndpointThresholds(750),
    'http_req_duration{scenario:read_catalog}': ['p(95)<600'],
    'http_req_duration{scenario:read_orders}': ['p(95)<600'],
    'http_req_duration{scenario:read_analytics}': ['p(95)<800'],
    'http_req_duration{scenario:read_audit_logs}': ['p(95)<800'],
    'http_req_duration{scenario:write_inventory}': ['p(95)<900'],
    'http_req_duration{scenario:write_orders}': ['p(95)<900'],
    'http_req_duration{scenario:order_lifecycle}': ['p(95)<1200'],
    'http_req_duration{scenario:auth_stress}': ['p(95)<1000'],
    'http_req_duration{scenario:cache_pressure}': ['p(95)<700'],
    'http_req_duration{scenario:mixed_realistic}': ['p(95)<750'],
  },
};

export function setup() {
  return setupTestData();
}

export function readCatalog(data) {
  browseProducts(data.baseUrl, data.staffToken, 'staff');
  sleep(randomInt(1, 2));
  listSuppliers(data.baseUrl, data.staffToken, 'staff');
  sleep(randomInt(1, 2));
}

export function readOrders(data) {
  listOrders(data.baseUrl, data.staffToken, 'staff');
  sleep(randomInt(1, 2));

  const order = pickRandom(data.orders);
  if (order) {
    getOrderDetail(data.baseUrl, data.staffToken, 'staff', order.id);
  }
  sleep(randomInt(1, 2));
  listNotifications(data.baseUrl, data.staffToken, 'staff');
  sleep(randomInt(1, 2));
}

export function readAnalytics(data) {
  getAnalyticsDashboard(data.baseUrl, data.adminToken);
  sleep(randomInt(2, 4));
  getAnalyticsDashboard(data.baseUrl, data.adminToken);
  sleep(randomInt(1, 2));
}

export function readAuditLogs(data) {
  getAuditLogs(data.baseUrl, data.adminToken, randomInt(0, 3), randomInt(50, 100));
  sleep(randomInt(2, 4));
}

export function writeInventory(data) {
  if (data.categories.length && data.warehouses.length && Math.random() < 0.4) {
    createProduct(data.baseUrl, data.adminToken, data.categories, data.warehouses, 'SKU-DIAG');
  }

  const product = pickRandom(data.products);
  if (product) {
    adjustStock(data.baseUrl, data.adminToken, product.id);
  }
  sleep(randomInt(1, 2));
}

export function writeOrders(data) {
  if (data.suppliers.length && data.warehouses.length && data.products.length) {
    createOrder(data.baseUrl, data.staffToken, 'staff', data.suppliers, data.warehouses, data.products);
  }
  sleep(randomInt(1, 2));
}

export function orderLifecycle(data) {
  if (data.suppliers.length && data.warehouses.length && data.products.length) {
    advanceOrderThroughLifecycle(
      data.baseUrl,
      data.staffToken,
      data.adminToken,
      data.suppliers,
      data.warehouses,
      data.products,
    );
  }
  sleep(randomInt(2, 4));
}

export function authStress(data) {
  login(data.baseUrl, STAFF_CREDENTIALS, 'staff');
  sleep(0.5);
}

export function cachePressure(data) {
  browseProducts(data.baseUrl, data.staffToken, 'staff');
  getAnalyticsDashboard(data.baseUrl, data.adminToken);

  if (Math.random() < 0.5 && data.products.length) {
    adjustStock(data.baseUrl, data.adminToken, pickRandom(data.products).id);
  } else if (data.suppliers.length && data.warehouses.length && data.products.length) {
    createOrder(data.baseUrl, data.staffToken, 'staff', data.suppliers, data.warehouses, data.products);
  }

  browseProducts(data.baseUrl, data.staffToken, 'staff');
  getAnalyticsDashboard(data.baseUrl, data.adminToken);
  sleep(randomInt(1, 2));
}

export function mixedRealistic(data) {
  if (Math.random() < 0.05) {
    checkHealth(data.baseUrl);
  }
  runMixedUserIteration(data, { skuPrefix: 'SKU-DIAG' });
}

export const handleSummary = createSummaryHandler('diagnostic-test');
