import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
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

    const dialog = screen.getByRole('dialog', { name: 'Prepara tu instalación de Linux' });
    expect(dialog).toBeInTheDocument();
    expect(screen.getByLabelText('Distribución')).toBeInTheDocument();
    expect(screen.getByLabelText('Arquitectura del equipo de destino')).toBeInTheDocument();
    expect(dialog.querySelector('[aria-live="polite"]')).toHaveAttribute('aria-busy', 'true');
    await waitFor(() => expect(resolvers.has('apt')).toBe(true));
    fireEvent.change(screen.getByLabelText('Distribución'), { target: { value: 'dnf' } });
    await waitFor(() => expect(resolvers.has('dnf')).toBe(true));
    await act(async () => { resolvers.get('dnf')?.(preview('dnf', 2)); });

    expect(await screen.findByText('2 instalables · 1 manuales · 0 no disponibles'))
      .toBeInTheDocument();
    expect(dialog.querySelector('[aria-live="polite"]')).toHaveAttribute('aria-busy', 'false');
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

  it('returns focus to the launcher when Escape closes the dialog', async () => {
    vi.spyOn(downloadsApi, 'previewLinuxDownload').mockResolvedValue(preview('apt', 1));
    function Harness() {
      const [open, setOpen] = useState(false);
      return <>
        <button type="button" onClick={() => setOpen(true)}>Open Linux</button>
        {open ? <LinuxTargetDialog request={{ appIds: ['app-id'], operatingSystems: ['linux'] }}
          onSelect={vi.fn()} onCancel={() => setOpen(false)} /> : null}
      </>;
    }
    render(<Harness />);
    const trigger = screen.getByRole('button', { name: 'Open Linux' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: 'Prepara tu instalación de Linux' });
    screen.getByLabelText('Distribución').focus();
    expect(trigger).not.toHaveFocus();
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    expect(dialog).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
