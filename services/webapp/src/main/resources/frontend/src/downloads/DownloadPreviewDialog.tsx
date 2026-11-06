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
    <header className="download-preview-header">
      <h2 id={titleId}>{t('download.preview.title')}</h2>
      <p>{t('download.preview.counts', { installers: preview.installerCount, manual: preview.manualCount, omitted: preview.omittedCount })}</p>
    </header>
    <dl className="download-preview-summary">
      <div><dt>{t('download.preview.known')}</dt><dd>{size(preview.knownBytes)}</dd></div>
      <div><dt>{t(preview.estimated ? 'download.preview.estimated' : 'download.preview.total')}</dt><dd>{size(preview.estimatedBytes)}</dd></div>
      <div><dt>{t('download.preview.unknownCount')}</dt><dd>{preview.unknownSizeCount}</dd></div>
      <div><dt>{t('download.preview.temporarySpace')}</dt><dd>{size(preview.estimatedPeakBytes)}</dd></div>
    </dl>
    <div className="download-preview-help">
      <p>{t('download.preview.sizeHelp')}</p>
      {preview.estimated ? <p>{t('download.preview.estimateHelp')}</p> : null}
    </div>
    {preview.warnings.map((warning) => <p className="download-preview-warning" role="status" key={warning}>{t(`download.preview.warning.${warning}`)}</p>)}
    <details className="download-preview-installers">
      <summary>{t('download.preview.installers', { count: preview.items.length })}</summary>
      <ol className="download-preview-items">{preview.items.map((item) => <li key={item.appId}>
      <div className="download-preview-item-heading">
        <strong>{item.name}</strong>
        {item.operatingSystem ? <span>{operatingSystemLabel(item.operatingSystem, t)} · {item.architecture}</span> : null}
      </div>
      {item.kind === 'installer' ? <>
        <p>{item.finalDomain || t('download.preview.sourceUnknown')}{item.filename ? <> · <code>{item.filename}</code></> : null}</p>
        <p>{t('download.preview.recordedSize')}: {size(item.sizeBytes)}</p>
      </> : null}
      {item.warnings.length ? <ul className="download-preview-item-warnings">{item.warnings.map((warning) => <li key={warning}>{t(`download.preview.warning.${warning}`)}</li>)}</ul> : null}
      </li>)}</ol>
    </details>
    <footer>
      <button className="secondary-button" type="button" onClick={onCancel}>{t('download.preview.edit')}</button>
      <button className="primary-button" type="button" disabled={confirming || preview.installerCount + preview.manualCount === 0}
        onClick={() => { if (!confirming) { setConfirming(true); onConfirm(); } }}>{t('download.preview.confirm')}</button>
    </footer>
  </dialog>;
}
