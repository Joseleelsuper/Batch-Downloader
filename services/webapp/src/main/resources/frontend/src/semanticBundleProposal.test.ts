import { describe, expect, it } from 'vitest';
import { semanticBundleProposal } from './semanticBundleProposal';
import type { CatalogApp, CatalogResponse } from './types/catalog';

const app: CatalogApp = {
  id: '00000000-0000-0000-0000-000000000001', slug: 'editor', packageId: 'Example.Editor',
  name: 'Editor', tags: ['video'], operatingSystems: ['windows'], downloadable: true,
  sourceLabel: 'Official', resolutionStatus: 'direct', validationStatus: 'valid', updatedAt: '',
};
const response = (data: CatalogApp[]): CatalogResponse => ({
  data, page: 1, pageSize: 12, total: data.length, appliedMode: 'semantic', requestedMode: 'semantic',
});

describe('semantic bundle proposal', () => {
  it('conserva la clasificación semántica, deduplica y excluye elementos no descargables', () => {
    const other = { ...app, id: '00000000-0000-0000-0000-000000000002' };
    const missing = { ...app, id: '00000000-0000-0000-0000-000000000003', downloadable: false };
    expect(semanticBundleProposal(response([other, app, missing, other]))).toEqual([other, app]);
  });

  it('acepta una propuesta vacía y no presenta el respaldo literal como una propuesta semántica', () => {
    expect(semanticBundleProposal(response([]))).toEqual([]);
    expect(semanticBundleProposal({ ...response([app]), appliedMode: 'lexical', degradedReason: 'semantic_unavailable' })).toEqual([]);
  });

  it('rechaza una respuesta con identificadores o plataformas fuera del esquema del catálogo', () => {
    expect(() => semanticBundleProposal(response([{ ...app, id: 'invented-app' }]))).toThrow('invalid_semantic_proposal');
    expect(() => semanticBundleProposal(response([{ ...app, operatingSystems: ['unknown'] } as unknown as CatalogApp]))).toThrow('invalid_semantic_proposal');
    expect(() => semanticBundleProposal({ ...response([]), data: null } as unknown as CatalogResponse)).toThrow('invalid_semantic_proposal');
  });

  it('limita la selección inicial a doce sin alterar los resultados recibidos', () => {
    const data = Array.from({ length: 15 }, (_, index) => ({ ...app,
      id: `00000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}` }));
    expect(semanticBundleProposal(response(data))).toHaveLength(12);
    expect(data).toHaveLength(15);
  });
});
