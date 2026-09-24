import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { deferredReply, serveApi } from '../test/http';
import Monitoring from './Monitoring';

describe('monitoring', () => {
  it('loads configuration from the API before exposing the separate Grafana login', async () => {
    const reply = deferredReply();
    const requests = serveApi({ 'GET /monitoring': () => reply.promise });
    render(<Monitoring />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading monitoring settings');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();

    await act(async () => reply.resolve({ data: { enabled: true, grafanaUrl: 'https://monitoring.example.test/grafana/' } }));
    const link = await screen.findByRole('link', { name: /Open Grafana\s*\(opens in a new tab\)/ });
    expect(link).toHaveAttribute('href', 'https://monitoring.example.test/grafana/');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText(/requires a separate operator login/)).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(requests.map(request => request.url)).toEqual(['/monitoring']);
  });

  it('explains how to enable dashboards when the environment has no Grafana configuration', async () => {
    serveApi({ 'GET /monitoring': { data: { enabled: false, grafanaUrl: null } } });
    render(<Monitoring />);
    expect(await screen.findByRole('heading', { name: 'Monitoring is not configured yet' })).toBeInTheDocument();
    expect(screen.getByText(/Ask the team managing this environment to connect Grafana/)).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('recovers from a failed configuration request with a visible retry state', async () => {
    let unavailable = true;
    const reply = deferredReply();
    const requests = serveApi({
      'GET /monitoring': () => unavailable ? { status: 503, data: {} } : reply.promise,
    });
    const user = userEvent.setup();
    render(<Monitoring />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load monitoring. Please try again.');
    unavailable = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(screen.getByRole('status')).toHaveTextContent('Loading monitoring settings');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await act(async () => reply.resolve({ data: { enabled: true, grafanaUrl: 'http://localhost:3000/' } }));
    expect(await screen.findByRole('link', { name: /Open Grafana/ })).toHaveAttribute('href', 'http://localhost:3000/');
    expect(requests).toHaveLength(2);
  });

  it.each([
    null,
    '',
    '/grafana',
    'javascript:alert(1)',
    'data:text/html,unsafe',
    'https://operator:secret@monitoring.example.test/',
    'https://operator@monitoring.example.test/',
    'https://:secret@monitoring.example.test/',
  ])('does not expose an invalid or credential-bearing external destination: %s', async grafanaUrl => {
    serveApi({ 'GET /monitoring': { data: { enabled: true, grafanaUrl } } });
    render(<Monitoring />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load monitoring');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('rejects an invalid configuration response instead of offering a link', async () => {
    serveApi({ 'GET /monitoring': { data: { enabled: 'true', grafanaUrl: 'https://monitoring.example.test' } } });
    render(<Monitoring />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load monitoring');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});
