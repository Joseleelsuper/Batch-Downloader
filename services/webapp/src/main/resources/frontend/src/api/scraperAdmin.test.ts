import { beforeEach, describe, expect, it, vi } from 'vitest';
import { requestJson } from './http';
import { regenerateScraperDescriptions } from './scraperAdmin';

vi.mock('./http', () => ({ API_BASE: '', requestJson: vi.fn() }));

describe('regenerateScraperDescriptions', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each(['ai', 'manual', 'all'] as const)('envía el ámbito %s por POST', async (scope) => {
    await regenerateScraperDescriptions(scope);
    expect(requestJson).toHaveBeenCalledWith(
      `/api/v1/admin/scraper/descriptions/regenerate?scope=${scope}`, { method: 'POST' },
    );
  });

  it('codifica el cursor de paginación', async () => {
    await regenerateScraperDescriptions('all', 'a&b');
    expect(requestJson).toHaveBeenCalledWith(
      '/api/v1/admin/scraper/descriptions/regenerate?scope=all&after=a%26b', { method: 'POST' },
    );
  });
});
