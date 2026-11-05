import { isCatalogAppSelectable } from './catalogSelection';
import type { CatalogApp, CatalogResponse } from './types/catalog';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SYSTEMS = new Set(['windows', 'linux', 'macos']);

/** La propuesta solo utiliza documentos del catálogo devueltos por la búsqueda semántica. */
export function semanticBundleProposal(response: CatalogResponse): CatalogApp[] {
  if (response.appliedMode !== 'semantic') return [];
  if (!Array.isArray(response.data)) throw new Error('invalid_semantic_proposal');
  const ids = new Set<string>();
  return response.data.filter((app) => {
    if (!app || !UUID.test(app.id) || typeof app.name !== 'string' || !app.name.trim()
      || !Array.isArray(app.tags) || !app.tags.every((tag) => typeof tag === 'string')
      || !Array.isArray(app.operatingSystems) || !app.operatingSystems.every((os) => SYSTEMS.has(os))
      || typeof app.downloadable !== 'boolean') throw new Error('invalid_semantic_proposal');
    if (ids.has(app.id) || !isCatalogAppSelectable(app)) return false;
    ids.add(app.id);
    return true;
  }).slice(0, 12);
}
