import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GlobalDownloadJobOverlay } from './GlobalDownloadJobOverlay';

vi.mock('./DownloadJobsContext', () => ({
  useDownloadJobs: () => ({
    jobs: Array.from({ length: 12 }, (_, index) => ({
      id: `job-${index}`,
      label: `Descarga ${index + 1}`,
      job: null,
      autoDownloadAttempted: false,
      minimized: false,
      cancelling: false,
      connectionError: false,
      actionError: null,
    })),
    startError: null,
    cancel: vi.fn(),
    dismiss: vi.fn(),
    toggleMinimized: vi.fn(),
    clearStartError: vi.fn(),
  }),
}));

afterEach(cleanup);

describe('GlobalDownloadJobOverlay', () => {
  it('keeps a long job list in a keyboard-scrollable region', () => {
    render(<GlobalDownloadJobOverlay />);

    const list = screen.getByRole('region', { name: 'Trabajos de descarga' });
    expect(list).toHaveAttribute('tabindex', '0');
    expect(within(list).getByText('Descarga 1')).toBeInTheDocument();
    expect(within(list).getByText('Descarga 12')).toBeInTheDocument();
  });
});
