import { useEffect, useRef, useState } from 'react';
import { previewLinuxDownload } from '../api/downloads';
import type { CreateDownloadJobRequest, LinuxArchitecture, LinuxPreview, LinuxSelection, LinuxTarget } from '../api/downloads';
import { useTranslation } from '../services/i18n';
import './linux-installer.css';

export function LinuxTargetDialog({ request, onSelect, onCancel }: Readonly<{
  request: CreateDownloadJobRequest;
  onSelect: (selection: LinuxSelection) => void;
  onCancel: () => void;
}>) {
  const t = useTranslation();
  const dialog = useRef<HTMLDialogElement>(null);
  const [target, setTarget] = useState<LinuxTarget>('apt');
  const [architecture, setArchitecture] = useState<LinuxArchitecture>('x86_64');
  const [result, setResult] = useState<{ key: string; preview?: LinuxPreview; error?: boolean } | null>(null);
  const key = target + ':' + architecture;
  const current = result?.key === key ? result : null;

  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);

  useEffect(() => {
    let active = true;
    void previewLinuxDownload({ ...request, linuxTarget: target, targetArchitecture: architecture })
      .then((preview) => { if (active) setResult({ key, preview }); })
      .catch(() => { if (active) setResult({ key, error: true }); });
    return () => { active = false; };
  }, [request, target, architecture, key]);

  const preview = current?.preview;
  return (
    <dialog ref={dialog} className="linux-installer-dialog" aria-labelledby="linux-target-title"
      onCancel={(event) => { event.preventDefault(); onCancel(); }}>
      <h2 id="linux-target-title">{t('linux.download.title')}</h2>
      <p>{t('linux.download.description')}</p>
      <label htmlFor="linux-target">{t('linux.download.target')}</label>
      <select id="linux-target" value={target} onChange={(event) => setTarget(event.target.value as LinuxTarget)}>
        <option value="apt">Ubuntu / Debian / Mint (APT)</option>
        <option value="dnf">Fedora / RHEL (DNF)</option>
        <option value="pacman">Arch / Manjaro (Pacman)</option>
        <option value="zypper">openSUSE (Zypper)</option>
        <option value="portable">{t('linux.download.portable')}</option>
      </select>
      <label htmlFor="linux-architecture">{t('linux.download.architecture')}</label>
      <select id="linux-architecture" value={architecture}
        onChange={(event) => setArchitecture(event.target.value as LinuxArchitecture)}>
        <option value="x86_64">x86_64 / AMD64</option>
        <option value="aarch64">aarch64 / ARM64</option>
        <option value="x86">x86 / i686</option>
      </select>
      <p><small>{t('linux.download.architectureHelp')} <code>uname -m</code></small></p>
      <div aria-live="polite" aria-busy={!current}>
        {!current ? <p>{t('common.loading')}</p> : current.error ? (
          <p role="alert">{t('linux.download.previewError')}</p>
        ) : preview ? (
          <>
            <p>{t('linux.download.counts', { automatic: preview.automaticCount, manual: preview.manualCount, omitted: preview.omittedCount })}</p>
            {preview.items.some((item) => item.dependency) ? (
              <details open><summary>{t('linux.download.dependencies')}</summary>
                <ul>{preview.items.filter((item) => item.dependency).map((item) => <li key={item.appId}>{item.name}</li>)}</ul>
              </details>
            ) : null}
          </>
        ) : null}
      </div>
      <p>{t('linux.download.instructions')}</p>
      <footer>
        <button type="button" className="secondary-button" onClick={onCancel}>{t('common.cancel')}</button>
        <button type="button" className="primary-button"
          disabled={!preview || preview.automaticCount + preview.manualCount === 0}
          onClick={() => onSelect({ linuxTarget: target, targetArchitecture: architecture })}>
          {t('linux.download.confirm')}
        </button>
      </footer>
    </dialog>
  );
}
