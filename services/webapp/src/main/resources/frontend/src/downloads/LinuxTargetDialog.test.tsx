import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as downloadsApi from '../api/downloads';
import type { LinuxPreview, LinuxTarget } from '../api/downloads';
import { LinuxTargetDialog } from './LinuxTargetDialog';

function preview(target: LinuxTarget, automaticCount: number): LinuxPreview {
  return {
    target,
    architecture: 'x86_64',
    totalCount: automaticCount + 1,
    automaticCount,
    manualCount: 1,
    omittedCount: 0,
    items: [{
      appId: 'dependency-id',
      name: `Dependencia ${target}`,
      sourceRef: 'source-id',
      installationSupport: 'automatic',
      dependency: true,
    }],
  };
}

describe('LinuxTargetDialog', () => {
  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value(this: HTMLDialogElement) { this.removeAttribute('open'); },
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('ignores stale previews and confirms the currently visible target', async () => {
    const resolvers = new Map<LinuxTarget, (value: LinuxPreview) => void>();
    vi.spyOn(downloadsApi, 'previewLinuxDownload').mockImplementation((request) => (
      new Promise((resolve) => {
        resolvers.set(request.linuxTarget ?? 'apt', resolve);
      })
    ));
    const onSelect = vi.fn();
    render(<LinuxTargetDialog
      request={{ appIds: ['app-id'], operatingSystems: ['linux'] }}
      onSelect={onSelect}
      onCancel={vi.fn()}
    />);

    await waitFor(() => expect(resolvers.has('apt')).toBe(true));
    fireEvent.change(screen.getByLabelText('Distribución'), { target: { value: 'dnf' } });
    await waitFor(() => expect(resolvers.has('dnf')).toBe(true));
    await act(async () => { resolvers.get('dnf')?.(preview('dnf', 2)); });

    expect(await screen.findByText('2 instalables · 1 manuales · 0 no disponibles'))
      .toBeInTheDocument();
    expect(screen.getByText('Dependencia dnf')).toBeInTheDocument();

    await act(async () => { resolvers.get('apt')?.(preview('apt', 7)); });
    expect(screen.queryByText('7 instalables · 1 manuales · 0 no disponibles'))
      .not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Preparar ZIP' }));
    expect(onSelect).toHaveBeenCalledWith({ linuxTarget: 'dnf', targetArchitecture: 'x86_64' });
  });

  it('keeps confirmation disabled on preview failure and remains cancellable', async () => {
    vi.spyOn(downloadsApi, 'previewLinuxDownload').mockRejectedValue(new Error('preview failed'));
    const onCancel = vi.fn();
    render(<LinuxTargetDialog
      request={{ appIds: ['app-id'], operatingSystems: ['linux'] }}
      onSelect={vi.fn()}
      onCancel={onCancel}
    />);

    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo preparar esta combinación');
    expect(screen.getByRole('button', { name: 'Preparar ZIP' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(onCancel).toHaveBeenCalledOnce();
  });
});
