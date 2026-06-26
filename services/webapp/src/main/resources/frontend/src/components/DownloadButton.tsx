import { Download } from 'lucide-react';
import { downloadUrl } from '../api/catalog';
import { t } from '../services/i18n';

interface Props {
  appId: string;
  disabled?: boolean;
}

export function DownloadButton({ appId, disabled }: Props) {
  return (
    <button
      className={`download-button ${disabled ? 'download-button-disabled' : ''}`}
      disabled={disabled}
      onClick={() => {
        window.location.assign(downloadUrl(appId));
      }}
      type="button"
    >
      <Download size={17} strokeWidth={2.4} />
      <span>{t('app.download')}</span>
    </button>
  );
}
