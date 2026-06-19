import {
  setupTestData,
  runMixedUserIteration,
  buildEndpointThresholds,
  createSummaryHandler,
} from './lib/common.js';

export const options = {
  stages: [
    { duration: '30s', target: 500 },
    { duration: '1m', target: 2000 },
    { duration: '1m', target: 3000 },
    { duration: '1m', target: 3000 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    ...buildEndpointThresholds(1500),
    http_req_failed: ['rate<0.10'], // We expect <10% failure rates now after optimization!
  },
};

export function setup() {
  return setupTestData();
}

export default function (data) {
  // Only Admin VUs (where __VU % 5 === 0) perform writes; staff VUs are read-only
  const includeWrites = (__VU % 5 === 0);
  runMixedUserIteration(data, { skuPrefix: 'SKU-EXTREME', thinkTime: [1, 2], includeWrites });
}

export const handleSummary = createSummaryHandler('high-load-stress-test');
