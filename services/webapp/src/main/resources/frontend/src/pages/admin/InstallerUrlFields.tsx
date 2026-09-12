import type { Dispatch, KeyboardEventHandler, SetStateAction } from 'react';
import { useTranslation } from '../../services/i18n';
import type { OperatingSystem } from '../../types/catalog';

/** Recoge una URL opcional por plataforma conservando etiquetas, límites y ayuda de cada flujo. */
export function InstallerUrlFields({ mode, values, onChange, disabled, onKeyDown }: {
  mode: 'manual' | 'website';
  values: Record<OperatingSystem, string>;
  onChange: Dispatch<SetStateAction<Record<OperatingSystem, string>>>;
  disabled: boolean;
  onKeyDown?: KeyboardEventHandler<HTMLInputElement>;
}) {
  const t = useTranslation();
  return <fieldset className={mode === 'manual' ? 'platform-installer-urls' : 'website-platform-urls'}>
    <legend>{t(mode === 'manual' ? 'admin.apps.manual.installerUrls' : 'admin.apps.website.optionalInstallers')}</legend>
    <p>{t(mode === 'manual' ? 'admin.apps.manual.installerUrlsHelp' : 'admin.apps.website.optionalInstallersHelp')}</p>
    <div>
      {(['windows', 'macos', 'linux'] as const).map((platform) => <label key={platform} htmlFor={`${mode}-${platform}-url`}>
        <span>{t(`admin.apps.${mode}.${platform}InstallerUrl`)}</span>
        <input id={`${mode}-${platform}-url`} type="url" inputMode="url" maxLength={2048}
          value={values[platform]} disabled={disabled} onKeyDown={onKeyDown}
          onChange={(event) => onChange((current) => ({ ...current, [platform]: event.target.value }))}
          placeholder="https://downloads.example.com/installer" autoComplete="url"
          aria-describedby={`${mode}-installer-urls-help`} />
      </label>)}
    </div>
    <small id={`${mode}-installer-urls-help`}>
      {t(mode === 'manual' ? 'admin.apps.manual.installerHelp' : 'admin.apps.website.installerUrlHelp')}
    </small>
  </fieldset>;
}
