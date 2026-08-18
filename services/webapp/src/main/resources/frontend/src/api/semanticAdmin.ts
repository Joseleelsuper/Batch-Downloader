import { requestJson } from './catalog';
import type {
  SemanticBenchmarkRun,
  SemanticModel,
  SemanticOperation,
  SemanticOperationAccepted,
  SemanticOverview,
} from '../types/semanticAdmin';

function idempotencyKey(action: string): string {
  const id = typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `semantic-${action}-${id}`;
}

function command<T>(
  path: string,
  action: string,
  init: RequestInit,
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Idempotency-Key', idempotencyKey(action));
  return requestJson<T>(path, {
    ...init,
    headers,
  });
}

export function fetchSemanticOverview(): Promise<SemanticOverview> {
  return requestJson<SemanticOverview>('/api/admin/semantic/overview');
}

export function fetchSemanticModels(): Promise<SemanticModel[]> {
  return requestJson<SemanticModel[]>('/api/admin/semantic/models');
}

export function fetchSemanticBenchmarks(): Promise<SemanticBenchmarkRun[]> {
  return requestJson<SemanticBenchmarkRun[]>('/api/admin/semantic/benchmarks');
}

export function startSemanticBenchmark(
  modelIds: string[],
): Promise<SemanticOperationAccepted> {
  return command('/api/admin/semantic/benchmarks', 'benchmark', {
    method: 'POST',
    body: JSON.stringify({ modelIds }),
  });
}

export function prepareSemanticModel(
  modelId: string,
): Promise<SemanticOperationAccepted> {
  return command(
    `/api/admin/semantic/models/${encodeURIComponent(modelId)}/prepare`,
    'prepare',
    { method: 'POST' },
  );
}

export function activateSemanticModel(
  modelId: string,
  payload: {
    benchmarkRunId: string;
    expectedCurrentModelId?: string | null;
    confirmRegression: boolean;
    activationKind?: 'activate' | 'rollback';
  },
): Promise<SemanticOperationAccepted> {
  return command(
    `/api/admin/semantic/models/${encodeURIComponent(modelId)}/activate`,
    'activate',
    { method: 'POST', body: JSON.stringify(payload) },
  );
}

export function deleteSemanticModel(
  modelId: string,
): Promise<SemanticOperationAccepted> {
  return command(
    `/api/admin/semantic/models/${encodeURIComponent(modelId)}`,
    'delete',
    { method: 'DELETE' },
  );
}

export function fetchSemanticOperations(
  active = false,
): Promise<SemanticOperation[]> {
  return requestJson<SemanticOperation[]>(
    `/api/admin/semantic/operations?active=${String(active)}`,
  );
}

export function cancelSemanticOperation(
  operationId: string,
): Promise<SemanticOperation> {
  return requestJson<SemanticOperation>(
    `/api/admin/semantic/operations/${encodeURIComponent(operationId)}`,
    { method: 'DELETE' },
  );
}

export function retrySemanticOperation(
  operationId: string,
): Promise<SemanticOperationAccepted> {
  return command(
    `/api/admin/semantic/operations/${encodeURIComponent(operationId)}/retry`,
    'retry',
    { method: 'POST' },
  );
}
