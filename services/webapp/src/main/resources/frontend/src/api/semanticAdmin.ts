import { requestJson } from './http';
import type { SemanticOverview } from '../types/semanticAdmin';

export function fetchSemanticOverview(): Promise<SemanticOverview> {
  return requestJson<SemanticOverview>('/api/v1/admin/semantic/overview');
}
