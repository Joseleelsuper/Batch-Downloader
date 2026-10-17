import { ChevronLeft, ChevronRight, Tags, X } from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createBundleCustomTag, fetchBundleCustomTags } from '../api/account';
import { createAdminBundleCustomTag, fetchAdminBundleCustomTags } from '../api/bundles';
import { fetchCatalogFacets } from '../api/catalogApps';
import { useLocale, useTranslation } from '../services/i18n';
import type { CatalogFacets } from '../types/catalog';

const MAX_TAGS = 30;
const MAX_TAG_LENGTH = 80;
const PAGE_SIZE = 60;
const cleanTag = (value: string) => value.trim().replace(/^-\s+/, '').trim();
const tagKey = (value: string) => cleanTag(value).toLowerCase();
const uniqueTags = (values: string[]) => [...new Map(values.map((value) => [tagKey(value), cleanTag(value)])).values()];
const searchKey = (value: string) => tagKey(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '');

interface Props {
  tags: string[];
  onChange: (tags: string[]) => void;
  administrator?: boolean;
}

export function BundleTagPicker({ tags, onChange, administrator = false }: Readonly<Props>) {
  const t = useTranslation();
  const [open, setOpen] = useState(false);
  const selectedTags = uniqueTags(tags);
  return <div className="bundle-tag-picker">
    <span className="bundle-tag-label">{t('bundleTags.tags')}</span>
    <button className="secondary-button compact-button" type="button" aria-haspopup="dialog" onClick={() => setOpen(true)}>
      <Tags size={18} aria-hidden="true" />{t('bundleTags.choose')}
    </button>
    {selectedTags.length ? <ul className="bundle-tag-selected">{selectedTags.map((tag) => <li key={tagKey(tag)}>
      <span>{tag}</span>
      <button type="button" aria-label={t('bundleTags.remove', { tag })}
        onClick={() => onChange(tags.filter((value) => tagKey(value) !== tagKey(tag)))}><X size={14} aria-hidden="true" /></button>
    </li>)}</ul> : null}
    {open ? <BundleTagDialog tags={tags} onChange={onChange} administrator={administrator} onClose={() => setOpen(false)} /> : null}
  </div>;
}

function BundleTagDialog({ tags, onChange, administrator, onClose }: Readonly<Props & { onClose: () => void }>) {
  const t = useTranslation();
  const locale = useLocale();
  const id = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [draft, setDraft] = useState(() => uniqueTags(tags));
  const [facets, setFacets] = useState<CatalogFacets | null>(null);
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [customTags, setCustomTags] = useState<string[] | null>(null);
  const [customFailed, setCustomFailed] = useState(false);
  const [customRetry, setCustomRetry] = useState(0);
  const [customSaving, setCustomSaving] = useState(false);
  const [customError, setCustomError] = useState(false);
  const [{ kind, query, page }, setFilter] = useState<{ kind: 'tags' | 'publishers' | 'custom'; query: string; page: number }>({
    kind: 'tags', query: '', page: 1,
  });
  const selected = new Set(draft.map(tagKey));

  useEffect(() => {
    const dialog = dialogRef.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);

  useEffect(() => {
    let active = true;
    setFailed(false);
    void fetchCatalogFacets({ query: '', filter: 'all', searchMode: 'lexical' })
      .then((result) => { if (active) setFacets(result); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [retry]);

  useEffect(() => {
    let active = true;
    setCustomFailed(false);
    void (administrator ? fetchAdminBundleCustomTags() : fetchBundleCustomTags())
      .then((result) => { if (active) setCustomTags((previous) => uniqueTags([...result, ...(previous ?? [])])); })
      .catch(() => { if (active) setCustomFailed(true); });
    return () => { active = false; };
  }, [customRetry, administrator]);

  const options = useMemo(() => {
    const unique = new Map<string, { value: string; label: string }>();
    const source = kind === 'custom'
      ? (customTags ?? []).map((value) => ({ value, label: value }))
      : facets?.[kind] ?? [];
    for (const item of source) {
      const value = cleanTag(item.value);
      const key = tagKey(value);
      if (!unique.has(key) || item.value === value) unique.set(key, { value, label: cleanTag(item.label) });
    }
    return [...unique.values()].sort((a, b) => a.label.localeCompare(b.label, locale));
  }, [facets, kind, locale, customTags]);
  const optionKeys = useMemo(() => new Set(options.map((item) => tagKey(item.value))), [options]);
  const catalogKeys = useMemo(() => new Set([...(facets?.tags ?? []), ...(facets?.publishers ?? [])]
    .map((item) => tagKey(item.value))), [facets]);
  const matches = useMemo(() => {
    const search = searchKey(query);
    const catalogMatches = options.filter((item) => searchKey(item.label).includes(search));
    if (kind !== 'custom') return catalogMatches;
    const customMatches = draft.filter((value) => !optionKeys.has(tagKey(value)) && !catalogKeys.has(tagKey(value))
      && searchKey(value).includes(search)).map((value) => ({ value, label: value }));
    return [...customMatches, ...catalogMatches];
  }, [options, kind, draft, optionKeys, catalogKeys, query]);
  const pages = Math.max(1, Math.ceil(matches.length / PAGE_SIZE));
  const visible = matches.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const customTag = cleanTag(query);
  const canAddCustom = kind !== 'publishers' && customTag && !selected.has(tagKey(customTag))
    && !optionKeys.has(tagKey(customTag));

  const listFailed = kind === 'custom' ? customFailed : failed;
  const listReady = kind === 'custom' ? customTags !== null : facets !== null;

  function toggle(value: string) {
    setDraft((previous) => previous.some((tag) => tagKey(tag) === tagKey(value))
      ? previous.filter((tag) => tagKey(tag) !== tagKey(value))
      : previous.length < MAX_TAGS && value.length <= MAX_TAG_LENGTH ? [...previous, value] : previous);
  }

  async function addCustom() {
    if (!canAddCustom || customSaving || draft.length >= MAX_TAGS || customTag.length > MAX_TAG_LENGTH) return;
    setCustomSaving(true); setCustomError(false);
    try {
      const created = await (administrator ? createAdminBundleCustomTag(customTag) : createBundleCustomTag(customTag));
      setCustomTags((previous) => uniqueTags([...(previous ?? []), created.tag]));
      setDraft((previous) => previous.some((tag) => tagKey(tag) === tagKey(created.tag))
        ? previous : previous.length < MAX_TAGS ? [...previous, created.tag] : previous);
      setFilter({ kind: 'custom', query: created.tag, page: 1 });
    } catch {
      setCustomError(true);
    } finally {
      setCustomSaving(false);
    }
  }

  return <dialog ref={dialogRef} className="bundle-tag-dialog" aria-labelledby={`${id}-title`}
    onCancel={(event) => { event.preventDefault(); onClose(); }}>
    <header>
      <h3 id={`${id}-title`}>{t('bundleTags.title')}</h3>
      <button className="icon-action" type="button" onClick={onClose} aria-label={t('common.close')}><X size={18} aria-hidden="true" /></button>
    </header>
    <input type="search" className="bundle-tag-search" aria-label={t('bundleTags.search')} placeholder={t('bundleTags.search')}
      value={query} onChange={(event) => { setCustomError(false); setFilter({ kind, query: event.target.value, page: 1 }); }}
      onKeyDown={(event) => { if (event.key === 'Enter') event.preventDefault(); }} />
    {canAddCustom ? <button type="button" className="secondary-button compact-button bundle-tag-custom"
      disabled={customSaving || draft.length >= MAX_TAGS || customTag.length > MAX_TAG_LENGTH}
      title={customTag.length > MAX_TAG_LENGTH ? t('bundleTags.tooLong', { limit: MAX_TAG_LENGTH }) : undefined}
      onClick={() => { void addCustom(); }}>{t('bundleTags.addCustom', { tag: customTag })}</button> : null}
    {customError ? <p role="alert" className="error-banner bundle-tag-error">{t('bundleTags.createFailed')}</p> : null}
    <div className="bundle-tag-categories" role="group" aria-label={t('bundleTags.title')}>
      {(['tags', 'publishers', 'custom'] as const).map((value) => <button key={value} type="button" aria-pressed={kind === value}
        onClick={() => setFilter({ kind: value, query, page: 1 })}>
        {t(`bundleTags.${value}`)}{value === 'custom'
          ? customTags ? ` (${customTags.length})` : ''
          : facets ? ` (${facets[value].length})` : ''}
      </button>)}
    </div>
    <output className="bundle-tag-count">{t('bundleTags.selected', { count: draft.length, limit: MAX_TAGS })}</output>
    {listFailed ? <div role="alert" className="error-banner bundle-tag-error">
      <p>{t(kind === 'custom' ? 'bundleTags.customFailed' : 'bundleTags.failed')}</p>
      <button className="secondary-button compact-button" type="button" onClick={() => {
        if (kind === 'custom') setCustomRetry((value) => value + 1);
        else setRetry((value) => value + 1);
      }}>{t('bundlePicker.retry')}</button>
    </div> : !listReady ? <p role="status">{t('common.loading')}</p> : <>
      <div className="bundle-tag-options">
        {visible.map((item) => {
          const checked = selected.has(tagKey(item.value));
          const tooLong = item.value.length > MAX_TAG_LENGTH;
          return <label className="bundle-tag-option" key={tagKey(item.value)} title={tooLong ? t('bundleTags.tooLong', { limit: MAX_TAG_LENGTH }) : item.label}>
            <input type="checkbox" checked={checked} disabled={!checked && (draft.length >= MAX_TAGS || tooLong)}
              onChange={() => toggle(item.value)} />
            <span>{item.label}</span>
          </label>;
        })}
        {!matches.length ? <p>{t('bundleTags.empty')}</p> : null}
      </div>
      <nav className="bundle-picker-pagination" aria-label={t('bundleTags.pagination')}>
        <button type="button" disabled={page === 1} aria-label={t('catalog.pagination.previous')}
          onClick={() => setFilter({ kind, query, page: page - 1 })}><ChevronLeft size={18} aria-hidden="true" /></button>
        <span>{t('bundlePicker.page', { page, pages })}</span>
        <button type="button" disabled={page >= pages} aria-label={t('catalog.pagination.next')}
          onClick={() => setFilter({ kind, query, page: page + 1 })}><ChevronRight size={18} aria-hidden="true" /></button>
      </nav>
    </>}
    <footer>
      <button type="button" className="secondary-button" onClick={onClose}>{t('common.cancel')}</button>
      <button type="button" className="primary-button" disabled={customSaving}
        onClick={() => { onChange(draft.map(cleanTag)); onClose(); }}>{t('bundleTags.apply')}</button>
    </footer>
  </dialog>;
}
