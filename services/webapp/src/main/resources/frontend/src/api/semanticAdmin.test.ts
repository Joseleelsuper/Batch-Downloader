import { beforeEach, describe, expect, it, vi } from 'vitest';
import { requestJson } from './catalog';
import {
  activateSemanticModel,
  cancelSemanticOperation,
  startSemanticBenchmark,
} from './semanticAdmin';

vi.mock('./catalog', () => ({
  requestJson: vi.fn(),
}));

describe('semantic administration client', () => {
  beforeEach(() => {
    vi.mocked(requestJson).mockReset().mockResolvedValue({ operationId: 'operation-1' });
  });

  it('sends benchmark commands with a stable command shape and idempotency key', async () => {
    await startSemanticBenchmark(['model-a', 'model-b']);

    expect(requestJson).toHaveBeenCalledWith(
      '/api/admin/semantic/benchmarks',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ modelIds: ['model-a', 'model-b'] }),
      }),
    );
    const init = vi.mocked(requestJson).mock.calls[0]?.[1];
    expect(new Headers(init?.headers).get('Idempotency-Key')).toMatch(
      /^semantic-benchmark-/,
    );
  });

  it('keeps optimistic activation data and rollback intent in the Core API request', async () => {
    await activateSemanticModel('candidate/model', {
      benchmarkRunId: 'benchmark-1',
      expectedCurrentModelId: 'active-1',
      confirmRegression: true,
      activationKind: 'rollback',
    });

    expect(requestJson).toHaveBeenCalledWith(
      '/api/admin/semantic/models/candidate%2Fmodel/activate',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          benchmarkRunId: 'benchmark-1',
          expectedCurrentModelId: 'active-1',
          confirmRegression: true,
          activationKind: 'rollback',
        }),
      }),
    );
  });

  it('encodes cancellation identifiers', async () => {
    await cancelSemanticOperation('operation/1');

    expect(requestJson).toHaveBeenCalledWith(
      '/api/admin/semantic/operations/operation%2F1',
      { method: 'DELETE' },
    );
  });
});
