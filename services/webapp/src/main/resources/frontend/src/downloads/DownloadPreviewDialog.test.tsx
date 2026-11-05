import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { downloadPreview, installModalDialogMethods } from '../test/downloadPreview';
import { DownloadPreviewDialog } from './DownloadPreviewDialog';

describe('DownloadPreviewDialog', () => {
  beforeEach(installModalDialogMethods);
  afterEach(cleanup);

  it('shows the source and recorded size with an explicit estimate explanation', () => {
    const onConfirm = vi.fn();
    render(<DownloadPreviewDialog preview={{ ...downloadPreview, estimated: true,
      unknownSizeCount: 1, estimatedBytes: 2048, estimatedPeakBytes: 4096 }} onConfirm={onConfirm} onCancel={vi.fn()} />);
    expect(screen.getByRole('dialog', { name: 'Revisa tu descarga' })).toBeInTheDocument();
    expect(screen.getByText(/example.test/)).toHaveTextContent('example.exe');
    expect(screen.getByText(/No indican el tamaño final del ZIP/)).toBeInTheDocument();
    expect(screen.getByText(/mediana/)).toBeInTheDocument();
    expect(screen.getByText('2 KiB')).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar descarga' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar descarga' }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('keeps unknown sizes explicit and explains manual links and unavailable apps', () => {
    render(<DownloadPreviewDialog preview={{ ...downloadPreview, installerCount: 0, manualCount: 1,
      omittedCount: 1, estimatedBytes: null, estimatedPeakBytes: null,
      items: [{ ...downloadPreview.items[0], name: '<script>example</script>',
        sourceRef: null, sizeBytes: null, kind: 'manual', warnings: ['manual_link'] },
      { ...downloadPreview.items[0], appId: 'missing', name: 'No disponible',
        kind: 'unavailable', warnings: ['unavailable'] }],
    }} onConfirm={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getAllByText('Desconocido')).toHaveLength(2);
    expect(screen.getByText('<script>example</script>')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
    expect(screen.getByText(/web oficial/)).toBeInTheDocument();
    expect(screen.getByText(/esta aplicación se omitirá/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar descarga' })).toBeEnabled();
  });

  it('cannot confirm an entirely unavailable selection', () => {
    render(<DownloadPreviewDialog preview={{ ...downloadPreview, installerCount: 0,
      omittedCount: 1, items: [] }} onConfirm={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Confirmar descarga' })).toBeDisabled();
  });

  it('cancels with Escape and restores the launcher focus', async () => {
    function Harness() {
      const [open, setOpen] = useState(false);
      return <><button type="button" onClick={() => setOpen(true)}>Revisar</button>
        {open ? <DownloadPreviewDialog preview={downloadPreview}
          returnFocusTo={document.activeElement as HTMLElement}
          onConfirm={vi.fn()} onCancel={() => setOpen(false)} /> : null}</>;
    }
    render(<Harness />);
    const trigger = screen.getByRole('button', { name: 'Revisar' });
    trigger.focus();
    fireEvent.click(trigger);
    screen.getByRole('button', { name: 'Volver a la selección' }).focus();
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
