import type { AuditItem, SoftwareRequestItem } from '../types/catalog';
import { requestJson } from './http';

export function fetchAdminRequests(): Promise<SoftwareRequestItem[]> {
  return requestJson('/api/v1/admin/requests');
}

export function fetchAdminAudit(): Promise<AuditItem[]> {
  return requestJson('/api/v1/admin/audit');
}
