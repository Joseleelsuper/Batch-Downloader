import { act, fireEvent, screen } from '@testing-library/react';
import type { DownloadPreview } from '../api/downloads';

export const downloadPreview: DownloadPreview = {
  requestedCount: 1, installerCount: 1, manualCount: 0, omittedCount: 0,
  knownBytes: 1024, estimatedBytes: 1024, estimated: false, unknownSizeCount: 0,
  estimatedPeakBytes: 2048, storageLimitBytes: 10 * 1024 ** 3,
  items: [{
    appId: 'app-1', name: 'Aplicación de ejemplo', sourceRef: 'source-1',
    operatingSystem: 'windows', architecture: 'x86_64', finalDomain: 'example.test',
    filename: 'example.exe', sizeBytes: 1024, kind: 'installer', warnings: [],
  }],
  warnings: [],
};

export function installModalDialogMethods() {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) { this.removeAttribute('open'); },
  });
}

export async function confirmDownloadPreview() {
  await act(async () => {});
  fireEvent.click(screen.getByRole('button', { name: 'Confirmar descarga' }));
  await act(async () => {});
}
