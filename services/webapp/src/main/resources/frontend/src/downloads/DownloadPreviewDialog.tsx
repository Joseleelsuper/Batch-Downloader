import { useEffect, useId, useRef, useState } from 'react';
import type { DownloadPreview } from '../api/downloads';
import { operatingSystemLabel } from '../components/OperatingSystemIcons';
import { useLocale, useTranslation } from '../services/i18n';
import './linux-installer.css';
import './download-preview.css';

export function DownloadPreviewDialog({ preview, returnFocusTo, onConfirm, onCancel }: Readonly<{
  preview: DownloadPreview;
  returnFocusTo?: HTMLElement | null;
  onConfirm: () => void;
  onCancel: () => void;
}>) {
  const t = useTranslation();
  const locale = useLocale();
  const titleId = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const [confirming, setConfirming] = useState(false);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => {
      element?.close();
      window.setTimeout(() => {
        if (returnFocusTo?.isConnected) returnFocusTo.focus({ preventScroll: true });
      }, 0);
    };
  }, [returnFocusTo]);

  function size(value: number | null) {
    if (value == null) return t('download.preview.unknown');
    const unit = value > 0 ? Math.min(3, Math.floor(Math.log(value) / Math.log(1024))) : 0;
    return `${(value / 1024 ** unit).toLocaleString(locale, { maximumFractionDigits: 2 })} ${['B', 'KiB', 'MiB', 'GiB'][unit]}`;
  }

  return <dialog ref={dialog} className="linux-installer-dialog download-preview-dialog" aria-labelledby={titleId}
    onCancel={(event) => { event.preventDefault(); onCancel(); }}>
    <h2 id={titleId}>{t('download.preview.title')}</h2>
    <p>{t('download.preview.counts', { installers: preview.installerCount, manual: preview.manualCount, omitted: preview.omittedCount })}</p>
    <dl>
      <dt>{t('download.preview.known')}</dt><dd>{size(preview.knownBytes)}</dd>
      <dt>{t(preview.estimated ? 'download.preview.estimated' : 'download.preview.total')}</dt><dd>{size(preview.estimatedBytes)}</dd>
      <dt>{t('download.preview.unknownCount')}</dt><dd>{preview.unknownSizeCount}</dd>
      <dt>{t('download.preview.temporarySpace')}</dt><dd>{size(preview.estimatedPeakBytes)}</dd>
    </dl>
    <p><small>{t('download.preview.sizeHelp')}</small></p>
    {preview.estimated ? <p>{t('download.preview.estimateHelp')}</p> : null}
    {preview.warnings.map((warning) => <p role="status" key={warning}>{t(`download.preview.warning.${warning}`)}</p>)}
    <ol>{preview.items.map((item) => <li key={item.appId}>
      <strong>{item.name}</strong>
      {item.operatingSystem ? <span> · {operatingSystemLabel(item.operatingSystem, t)} {item.architecture}</span> : null}
      {item.kind === 'installer' ? <>
        <p>{item.finalDomain || t('download.preview.sourceUnknown')}{item.filename ? <> · <code>{item.filename}</code></> : null}</p>
        <p>{t('download.preview.recordedSize')}: {size(item.sizeBytes)}</p>
      </> : null}
      {item.warnings.length ? <ul>{item.warnings.map((warning) => <li key={warning}>{t(`download.preview.warning.${warning}`)}</li>)}</ul> : null}
    </li>)}</ol>
    <footer>
      <button className="secondary-button" type="button" onClick={onCancel}>{t('download.preview.edit')}</button>
      <button className="primary-button" type="button" disabled={confirming || preview.installerCount + preview.manualCount === 0}
        onClick={() => { if (!confirming) { setConfirming(true); onConfirm(); } }}>{t('download.preview.confirm')}</button>
    </footer>
  </dialog>;
}
