import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DownloadJob } from '../types/catalog';
import { BundleDownloadButton } from './BundleDownloadButton';

const hooks = vi.hoisted(() => ({
  useDownloadJob: vi.fn(),
}));

vi.mock('../hooks/useDownloadJob', () => hooks);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function activeJob(): DownloadJob {
  return {
    id: 'a51d185b-1966-4c51-83e2-9b2f523f47ce',
    status: 'DOWNLOADING',
    progress: 35,
    requestedCount: 3,
    acceptedCount: 2,
    omittedCount: 1,
    createdAt: '2026-07-11T08:00:00Z',
    expiresAt: '2026-07-12T08:00:00Z',
    items: [],
  };
}

describe('BundleDownloadButton', () => {
  it('mantiene el progreso y la cancelación accesibles en una tarjeta compacta', () => {
    hooks.useDownloadJob.mockReturnValue({
      job: activeJob(),
      starting: false,
      cancelling: false,
      error: false,
      start: vi.fn(),
      cancel: vi.fn(),
      clear: vi.fn(),
    });

    render(<BundleDownloadButton bundleId="bundle-1" appCount={3} compact />);

    expect(screen.getByLabelText('Trabajo de descarga')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    expect(screen.getByText(/1 omitida/i)).toBeInTheDocument();
  });
});
