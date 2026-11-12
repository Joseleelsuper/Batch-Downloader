import { requestJson } from './http';

export type SoftwareRequestStatus = 'pending' | 'accepted' | 'rejected';
export interface SoftwareRequest {
  id: string;
  name: string;
  officialUrl: string;
  status: SoftwareRequestStatus;
  reviewNote: string;
  createdAt: string;
  reviewedAt: string | null;
  version: number;
}
export interface SoftwareRequestPage {
  data: SoftwareRequest[]; page: number; pageSize: number; total: number;
}

export function fetchOwnSoftwareRequests(page: number, pageSize: number, signal?: AbortSignal) {
  return requestJson<SoftwareRequestPage>(`/api/v1/users/me/software-requests?page=${page}&pageSize=${pageSize}`, { signal });
}

export function createSoftwareRequest(name: string, officialUrl: string) {
  return requestJson<SoftwareRequest>('/api/v1/users/me/software-requests', {
    method: 'POST', body: JSON.stringify({ name, officialUrl }),
  });
}

export function fetchAdminSoftwareRequests(page: number, pageSize: number,
  status?: SoftwareRequestStatus, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  if (status) params.set('status', status);
  return requestJson<SoftwareRequestPage>(`/api/v1/admin/software-requests?${params}`, { signal });
}

export function reviewSoftwareRequest(requestId: string, status: Exclude<SoftwareRequestStatus, 'pending'>,
  reviewNote: string, version: number) {
  return requestJson<SoftwareRequest>(`/api/v1/admin/software-requests/${encodeURIComponent(requestId)}`, {
    method: 'PATCH', body: JSON.stringify({ status, reviewNote, version }),
  });
}
