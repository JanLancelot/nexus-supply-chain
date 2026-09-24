import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { pageOf, products } from '../test/fixtures';
import { deferredReply, serveApi } from '../test/http';
import type { Product } from '../types';
import ProductPicker from './ProductPicker';

function Picker() {
  const [selected, setSelected] = useState<Product>();
  return <ProductPicker warehouseId="warehouse-1" value={selected} onChange={setSelected} />;
}

const firstPage = '/inventory/products?page=0&size=50&warehouseId=warehouse-1&active=true';
const secondPage = '/inventory/products?page=1&size=50&warehouseId=warehouse-1&active=true';

describe('product picker', () => {
  it('finds products beyond the first 50 and preserves selection across result pages', async () => {
    const first50 = Array.from({ length: 50 }, (_, index) => ({ ...products[0], id: `product-${index}`, sku: `SKU-${index}` }));
    const later = { ...products[1], id: 'product-51' };
    const requests = serveApi({
      [`GET ${firstPage}`]: { data: pageOf(first50, 0, true) },
      [`GET ${secondPage}`]: { data: pageOf([later], 1) },
    });
    const user = userEvent.setup();
    render(<Picker />);
    await screen.findByRole('option', { name: /SKU-49/ });
    expect(screen.queryByRole('option', { name: /PAPER-02/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next products' }));
    await screen.findByRole('option', { name: /PAPER-02/ });
    await user.selectOptions(screen.getByRole('combobox'), 'product-51');
    await user.click(screen.getByRole('button', { name: 'Previous products' }));
    await screen.findByRole('option', { name: /SKU-49/ });
    expect(screen.getByRole('combobox')).toHaveValue('product-51');
    expect(requests.map(request => request.url)).toEqual([firstPage, secondPage, firstPage]);
  });

  it('searches by name or SKU on the server and excludes inactive or wrong-warehouse responses', async () => {
    const requests = serveApi({
      [`GET ${firstPage}`]: { data: pageOf([products[0], { ...products[1], isActive: false }, { ...products[1], id: 'elsewhere', warehouseId: 'other' }]) },
      'GET /inventory/products?page=0&size=50&search=PAPER+02&warehouseId=warehouse-1&active=true': { data: pageOf([products[1]]) },
    });
    const user = userEvent.setup();
    render(<Picker />);
    await screen.findByRole('option', { name: /SOAP-01/ });
    expect(screen.queryByRole('option', { name: /PAPER-02/ })).not.toBeInTheDocument();
    await user.type(screen.getByRole('textbox'), ' PAPER 02 ');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('option', { name: /PAPER-02/ })).toBeInTheDocument();
    expect(requests[1].url).toContain('search=PAPER+02');
  });

  it('retries a failed page without skipping it', async () => {
    let fail = true;
    serveApi({
      [`GET ${firstPage}`]: { data: pageOf([products[0]], 0, true) },
      [`GET ${secondPage}`]: () => fail ? { status: 503, data: { message: 'Products unavailable' } } : { data: pageOf([products[1]], 1) },
    });
    const user = userEvent.setup();
    render(<Picker />);
    await screen.findByRole('option', { name: /SOAP-01/ });
    await user.click(screen.getByRole('button', { name: 'Next products' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Products unavailable');
    expect(screen.getByText('Product results page 1')).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Next products' }));
    expect(await screen.findByRole('option', { name: /PAPER-02/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('ignores a late search response after another search succeeds', async () => {
    const slow = deferredReply();
    serveApi({
      [`GET ${firstPage}`]: { data: pageOf([products[0]]) },
      'GET /inventory/products?page=0&size=50&search=slow&warehouseId=warehouse-1&active=true': () => slow.promise,
      'GET /inventory/products?page=0&size=50&search=paper&warehouseId=warehouse-1&active=true': { data: pageOf([products[1]]) },
    });
    const user = userEvent.setup();
    render(<Picker />);
    await screen.findByRole('option', { name: /SOAP-01/ });
    await user.type(screen.getByRole('textbox'), 'slow{Enter}');
    await user.clear(screen.getByRole('textbox'));
    await user.type(screen.getByRole('textbox'), 'paper{Enter}');
    await screen.findByRole('option', { name: /PAPER-02/ });
    slow.resolve({ data: pageOf([products[0]]) });
    expect(screen.getByRole('option', { name: /PAPER-02/ })).toBeInTheDocument();
  });
});
