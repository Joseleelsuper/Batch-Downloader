import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as accountApi from '../api/account';
import * as bundlesApi from '../api/bundles';
import * as catalogAppsApi from '../api/catalogApps';
import { t } from '../services/i18n';
import type { FacetItem } from '../types/catalog';
import { BundleTagPicker } from './BundleTagPicker';

const facet = (value: string): FacetItem => ({
  value, label: value, normalizedValue: value.toLowerCase(), letter: value[0], count: 1,
});

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true, value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true, value(this: HTMLDialogElement) { this.removeAttribute('open'); },
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('BundleTagPicker', () => {
  it('recupera las facetas y permite elegir entre páginas y buscar editores sin acentos', async () => {
    const tags = Array.from({ length: 61 }, (_, index) => facet(`Category${String(index + 1).padStart(2, '0')}`));
    const fetchFacets = vi.spyOn(catalogAppsApi, 'fetchCatalogFacets')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce({ tags, publishers: [facet('Éditeur')] });
    vi.spyOn(accountApi, 'fetchBundleCustomTags').mockResolvedValue([]);
    const onChange = vi.fn();

    render(<BundleTagPicker tags={[]} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.choose') }));
    expect(await screen.findByText(t('bundleTags.failed'))).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('bundlePicker.retry') }));
    expect(await screen.findByRole('checkbox', { name: 'Category01' })).toBeInTheDocument();
    expect(fetchFacets).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole('button', { name: t('catalog.pagination.next') }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Category61' }));
    fireEvent.click(screen.getByRole('button', { name: `${t('bundleTags.publishers')} (1)` }));
    fireEvent.change(screen.getByRole('searchbox', { name: t('bundleTags.search') }), {
      target: { value: 'editeur' },
    });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Éditeur' }));
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.apply') }));
    expect(onChange).toHaveBeenCalledWith(['Category61', 'Éditeur']);
  });

  it('muestra un error al crear una etiqueta y permite reintentarla', async () => {
    vi.spyOn(catalogAppsApi, 'fetchCatalogFacets').mockResolvedValue({ tags: [], publishers: [] });
    vi.spyOn(accountApi, 'fetchBundleCustomTags').mockResolvedValue(['Old Tag']);
    const create = vi.spyOn(accountApi, 'createBundleCustomTag')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce({ tag: 'New Tag' });
    const onChange = vi.fn();

    render(<BundleTagPicker tags={[]} onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.choose') }));
    expect(await screen.findByRole('button', { name: `${t('bundleTags.custom')} (1)` })).toBeInTheDocument();
    const search = screen.getByRole('searchbox', { name: t('bundleTags.search') });
    fireEvent.change(search, { target: { value: ' New Tag ' } });
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.addCustom', { tag: 'New Tag' }) }));
    expect(await screen.findByText(t('bundleTags.createFailed'))).toBeInTheDocument();
    fireEvent.change(search, { target: { value: 'New Tag' } });
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.addCustom', { tag: 'New Tag' }) }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('checkbox', { name: 'New Tag' })).toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.apply') }));
    expect(onChange).toHaveBeenCalledWith(['New Tag']);
  });

  it('usa las etiquetas administrativas y permite recuperar su listado', async () => {
    vi.spyOn(catalogAppsApi, 'fetchCatalogFacets').mockResolvedValue({ tags: [], publishers: [] });
    const fetchCustom = vi.spyOn(bundlesApi, 'fetchAdminBundleCustomTags')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(['Shared']);
    const create = vi.spyOn(bundlesApi, 'createAdminBundleCustomTag').mockResolvedValue({ tag: 'Team' });
    const onChange = vi.fn();

    render(<BundleTagPicker tags={['Personal']} onChange={onChange} administrator />);
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.choose') }));
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.custom') }));
    expect(await screen.findByText(t('bundleTags.customFailed'))).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('bundlePicker.retry') }));
    expect(await screen.findByRole('checkbox', { name: 'Shared' })).not.toBeChecked();
    expect(fetchCustom).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('checkbox', { name: 'Personal' })).toBeChecked();

    fireEvent.change(screen.getByRole('searchbox', { name: t('bundleTags.search') }), {
      target: { value: 'Team' },
    });
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.addCustom', { tag: 'Team' }) }));
    await waitFor(() => expect(create).toHaveBeenCalledWith('Team'));
    expect(await screen.findByRole('checkbox', { name: 'Team' })).toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: t('bundleTags.apply') }));
    expect(onChange).toHaveBeenCalledWith(['Personal', 'Team']);
  });
});
