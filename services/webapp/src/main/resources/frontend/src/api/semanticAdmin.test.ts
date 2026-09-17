import { beforeEach, describe, expect, it, vi } from 'vitest';
import { requestJson } from './http';
import { fetchSemanticOverview } from './semanticAdmin';

vi.mock('./http', () => ({ requestJson: vi.fn() }));

describe('semantic status client', () => {
  beforeEach(() => vi.mocked(requestJson).mockReset());

  it('consulta solamente el resumen administrativo de solo lectura', async () => {
    vi.mocked(requestJson).mockResolvedValue({ status: 'ok' });

    await fetchSemanticOverview();

    expect(requestJson).toHaveBeenCalledWith('/api/v1/admin/semantic/overview');
  });
});
