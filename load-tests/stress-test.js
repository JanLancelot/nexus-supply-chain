import {
  setupTestData,
  runMixedUserIteration,
  buildEndpointThresholds,
  createSummaryHandler,
} from './lib/common.js';

export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '1m', target: 500 },
    { duration: '1m', target: 1000 },
    { duration: '2m', target: 1000 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    ...buildEndpointThresholds(1000),
    http_req_failed: ['rate<0.05'],
  },
};

export function setup() {
  return setupTestData();
}

export default function (data) {
  runMixedUserIteration(data, { skuPrefix: 'SKU-STRESS', thinkTime: [1, 2] });
}

export const handleSummary = createSummaryHandler('stress-test');
