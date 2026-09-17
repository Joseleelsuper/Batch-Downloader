import { useTranslation } from '../../services/i18n';
import { clickableHttpUrl, EditorField, EditorTextarea, suggestionProvenance } from './AdminAppsSupport';
import type { useAdminAppEditor } from './useAdminAppEditor';

const PRIMARY_FIELDS = [
  { key: 'name', id: 'name', label: 'admin.field.name' },
  { key: 'publisher', id: 'publisher', label: 'admin.field.publisher' },
  { key: 'officialUrl', id: 'official-url', label: 'admin.field.officialUrl' },
  { key: 'latestVersion', id: 'version', label: 'admin.field.latestVersion' },
  { key: 'iconUrl', id: 'icon', label: 'admin.apps.field.iconUrl' },
] as const;
const DESCRIPTION_FIELDS = [
  { key: 'description', id: 'description', label: 'admin.app.shortDescription', rows: undefined },
  { key: 'longDescription', id: 'long-description', label: 'admin.app.longDescription', rows: 7 },
] as const;

/** Edita los metadatos y muestra su procedencia mientras coincidan con la sugerencia recibida. */
export function AdminAppFields({ editor }: Readonly<{ editor: ReturnType<typeof useAdminAppEditor> }>) {
  const t = useTranslation();
  const { form, provenance, previewPending, setEditor } = editor;
  const fieldProps = (key: keyof typeof form) => ({
    value: form[key],
    disabled: previewPending,
    provenance: suggestionProvenance(provenance?.[key], form[key]),
    onChange: (value: string) => setEditor('form', (current) => ({ ...current, [key]: value })),
  });
  return <>
    <fieldset className="admin-app-editor-section">
      <legend>{t('admin.app.primaryData')}</legend>
      <div className="admin-app-form-grid">
        {PRIMARY_FIELDS.map(({ key, id, label }) => <EditorField
          key={key} id={`admin-app-${id}`} label={t(label)} {...fieldProps(key)}
          required={key === 'name'}
          type={key === 'officialUrl' || key === 'iconUrl' ? 'url' : undefined}
          externalHref={key === 'officialUrl' ? clickableHttpUrl(form.officialUrl) : undefined}
          externalLabel={key === 'officialUrl' ? t('admin.apps.openOfficialUrl') : undefined}
        />)}
      </div>
    </fieldset>
    <fieldset className="admin-app-editor-section">
      <legend>{t('admin.app.form.description')}</legend>
      {DESCRIPTION_FIELDS.map(({ key, id, label, rows }) => <EditorTextarea
        key={key} id={`admin-app-${id}`} label={t(label)} rows={rows} {...fieldProps(key)}
      />)}
    </fieldset>
  </>;
}
