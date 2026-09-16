import type { AuditItem } from '../types/catalog';
import { requestJson } from './http';

export function fetchAdminAudit(): Promise<AuditItem[]> {
  return requestJson('/api/v1/admin/audit');
}
