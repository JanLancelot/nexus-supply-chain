import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { pageOf, products, suppliers } from '../test/fixtures';
import { serveApi } from '../test/http';
import ReferenceData from './ReferenceData';

const emptySetup = {
  'GET /categories': { data: [] },
  'GET /warehouses': { data: [] },
  'GET /suppliers': { data: [] },
};

describe('reference data setup', () => {
  it('provisions all required reference data from an empty installation', async () => {
    const requests = serveApi({ ...emptySetup,
      'POST /categories': { data: { id: 'category-1', name: 'Office', description: 'Paper' } },
      'POST /warehouses': { data: { id: 'warehouse-1', name: 'Main', location: 'Manila' } },
      'POST /suppliers': { data: { ...suppliers[0], name: 'Paper Supply' } },
    });
    const user = userEvent.setup();
    render(<MemoryRouter><ReferenceData /></MemoryRouter>);
    await screen.findByText('No categories yet.');
    await user.type(screen.getByLabelText('Category name'), 'Office');
    await user.type(screen.getByLabelText('Description'), 'Paper');
    await user.click(screen.getByRole('button', { name: 'Create category' }));
    await screen.findByText('Category Office created.');
    await user.type(screen.getByLabelText('Warehouse name'), 'Main');
    await user.type(screen.getByLabelText('Location'), 'Manila');
    await user.click(screen.getByRole('button', { name: 'Create warehouse' }));
    await screen.findByText('Warehouse Main created.');
    await user.type(screen.getByLabelText('Supplier name'), 'Paper Supply');
    await user.type(screen.getByLabelText('Email'), 'paper@example.com');
    await user.click(screen.getByRole('button', { name: 'Create supplier' }));
    await screen.findByText('Supplier Paper Supply created.');
    expect(requests.filter(request => request.method === 'POST').map(request => [request.url, request.body])).toEqual([
      ['/categories', { name: 'Office', description: 'Paper' }],
      ['/warehouses', { name: 'Main', location: 'Manila' }],
      ['/suppliers', { name: 'Paper Supply', email: 'paper@example.com', contactPerson: '', phone: '', address: '', leadTimeDays: 3, isActive: true, productIds: [] }],
    ]);
    expect(screen.getByLabelText('Supplier name')).toHaveValue('');
  });

  it('loads current supplier products before replacing sourcing assignments', async () => {
    const requests = serveApi({ ...emptySetup,
      'GET /suppliers': { data: [{ ...suppliers[0], productIds: ['product-1'] }] },
      'GET /inventory/products/product-1': { data: products[0] },
      'GET /inventory/products?page=0&size=50&active=true': { data: pageOf(products) },
      'PUT /suppliers/supplier-1/products': { data: { productIds: ['product-2'] } },
    });
    const user = userEvent.setup();
    render(<MemoryRouter><ReferenceData /></MemoryRouter>);
    await user.click(await screen.findByRole('button', { name: 'Manage products for North Supply' }));
    const form = await screen.findByRole('form', { name: 'Supplier product sourcing' });
    expect(within(form).getByText('SOAP-01 — Hand Soap')).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Remove SOAP-01' }));
    await user.selectOptions(within(form).getByRole('combobox'), 'product-2');
    await user.click(within(form).getByRole('button', { name: 'Save supplier products' }));
    await screen.findByText('Products assigned to North Supply.');
    expect(requests.find(request => request.method === 'PUT')?.body).toEqual({ productIds: ['product-2'] });
  });

  it('preserves input and reports supplier validation failures', async () => {
    serveApi({ ...emptySetup, 'POST /suppliers': { status: 400, data: { message: 'Supplier name is required' } } });
    const user = userEvent.setup();
    render(<MemoryRouter><ReferenceData /></MemoryRouter>);
    await screen.findByText('No categories yet.');
    await user.type(screen.getByLabelText('Supplier name'), '  ');
    await user.click(screen.getByRole('button', { name: 'Create supplier' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Supplier name is required');
    expect(screen.getByLabelText('Supplier name')).toHaveValue('  ');
  });
});
