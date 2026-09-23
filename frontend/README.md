# Nexus Supply Chain frontend

React, TypeScript, and Vite client for the inventory and purchase order API. Administrators can manage users and stock, approve orders, and inspect audit logs. Staff can browse products and create purchase orders. The API enforces permissions; route guards only control navigation.

## Development

Use Node.js 24 or newer. From this directory:

```sh
npm ci
npm run dev
```

Vite proxies `/api` to `http://localhost:8080`. Start the backend using the repository README. Sign in with an account created by your administrator or configured by the backend's optional demo initializer. Credentials are not embedded in this client.

## Checks

```sh
npm run lint
npm test
npm run build
npm audit
```

Lint fails on warnings. The build includes strict TypeScript checks. The tests cover session parsing, expiration, request authorization, stale unauthorized responses, and safe error messages. `npm audit` requires access to the npm registry.

## Session behavior

Bearer tokens live only in memory and are cleared on logout, expiration, or an unauthorized response for the current session. Reloading or closing the page requires signing in again. Tokens from older versions are removed from local storage at startup. The JWT payload supplies display data; signature verification and access control belong to the backend. Memory storage reduces credential persistence but cannot prevent active malicious JavaScript from using a signed-in session.

## Production

The Dockerfile builds static assets and serves them with Nginx. Set `BACKEND_URL` to the backend origin without a trailing slash, for example `http://backend:8080`; Nginx preserves the `/api/...` request path. The root Compose configuration instead builds the React bundle into the Spring Boot image; it does not start this separate Nginx container.

The Nginx template sets a Content Security Policy, blocks framing, and restricts scripts and API connections to the same origin. The font stylesheet and font files are allowed from Google Fonts. React updates chart widths through DOM style properties, which remain compatible with `style-src-attr 'none'`; inline style attributes supplied as HTML are blocked. HTTPS upstream certificates are verified against the container's CA bundle; private upstream CAs must be added to that bundle.

Terminate public HTTPS at your deployment edge. `npm run preview` is for inspecting a local build and does not apply the Nginx response headers. A different static hosting platform must configure equivalent headers and `/api` proxying.

## Data display

Catalog, order, and audit filters apply to the currently loaded page. Order creation loads the first 50 products (the API page-size limit). The dashboard's delivered order value is the total of delivered purchase orders, not sales revenue. Low-stock messages use the API's recorded reorder thresholds, not a broader anomaly detection system.
