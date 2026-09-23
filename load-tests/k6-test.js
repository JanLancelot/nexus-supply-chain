import {
  setupTestData,
  scaledVus,
  runMixedUserIteration,
  buildEndpointThresholds,
  createSummaryHandler,
} from './lib/common.js';

export const options = {
  stages: [
    { duration: '30s', target: scaledVus(100) },
    { duration: '1m', target: scaledVus(300) },
    { duration: '2m', target: scaledVus(300) },
    { duration: '30s', target: 0 },
  ],
  thresholds: buildEndpointThresholds(500),
};

export function setup() {
  return setupTestData();
}

export default function (data) {
  runMixedUserIteration(data, { skuPrefix: 'SKU-LOAD' });
}

export const handleSummary = createSummaryHandler('load-test');
