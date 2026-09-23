import { AxiosError, type AxiosResponse } from 'axios';
import { afterEach, expect } from 'vitest';
import api from '../services/api';

export interface HttpRequest {
  method: string;
  url: string;
  body: unknown;
}

interface HttpReply {
  data: unknown;
  status?: number;
}

type Route = HttpReply | ((request: HttpRequest) => HttpReply | Promise<HttpReply>);
const originalAdapter = api.defaults.adapter;
let unexpectedRequests: string[] = [];

afterEach(() => {
  api.defaults.adapter = originalAdapter;
  const unexpected = unexpectedRequests;
  unexpectedRequests = [];
  expect(unexpected, 'Every HTTP request must have an explicit test response').toEqual([]);
});

// Replace only the network transport: application services, interceptors and
// authentication all run as they do in the browser.
export function serveApi(routes: Record<string, Route>) {
  const requests: HttpRequest[] = [];
  api.defaults.adapter = async (config) => {
    const request = {
      method: (config.method ?? 'get').toUpperCase(),
      url: config.url ?? '',
      body: typeof config.data === 'string' ? JSON.parse(config.data) as unknown : config.data,
    };
    requests.push(request);
    const key = `${request.method} ${request.url}`;
    const route = routes[key];
    if (!route) {
      unexpectedRequests.push(key);
      throw new Error(`Unexpected HTTP request: ${key}`);
    }
    const reply = typeof route === 'function' ? await route(request) : route;
    const response: AxiosResponse = {
      data: reply.data, status: reply.status ?? 200, statusText: '', headers: {}, config,
    };
    if (response.status >= 400) {
      throw new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, response);
    }
    return response;
  };
  return requests;
}

export function deferredReply() {
  let resolve!: (reply: HttpReply) => void;
  const promise = new Promise<HttpReply>((done) => { resolve = done; });
  return { promise, resolve };
}
