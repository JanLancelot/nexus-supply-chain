import type { ReactElement } from 'react';
import { render, screen, within } from '@testing-library/react';
import { AuthProvider } from '../context/AuthContext';
import { parseSession, setSession } from '../services/session';
import { sessionToken } from './session';

export function renderAuthenticated(element: ReactElement, role = 'ROLE_ADMIN') {
  setSession(parseSession(sessionToken({ role })));
  return render(<AuthProvider>{element}</AuthProvider>);
}

// Existing select controls expose their choices but do not all have associated
// labels. Locate a select by one of its user-visible options, never its position.
export function selectWithOption(name: string) {
  const select = screen.getAllByRole('combobox').find((element) =>
    within(element).queryByRole('option', { name }),
  );
  if (!select) throw new Error(`No select offers the option "${name}"`);
  return select;
}
