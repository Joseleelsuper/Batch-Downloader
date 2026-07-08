import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppStatusBadge } from './AppStatusBadge';

describe('AppStatusBadge', () => {
  it('renders direct status in Spanish', () => {
    render(<AppStatusBadge status="direct" />);
    expect(screen.getByText('Directa')).toBeInTheDocument();
  });
});
