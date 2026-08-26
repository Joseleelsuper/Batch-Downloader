import type {
  AdminAppFilter,
  AppDetails,
  CatalogResponse,
  InstallerAbsenceVerification,
  InstallerAbsenceVerificationRequest,
  InstallerAbsenceVerificationSummary,
  ManualInstallerApplyRequest,
  ManualInstallerApplyResponse,
  ManualInstallerInspection,
  ManualInstallerInspectionRequest,
  SortKey,
  WebsiteAppDiscovery,
  WebsiteAppDiscoveryApplyRequest,
  WebsiteAppDiscoveryApplyResponse,
  WebsiteAppDiscoveryRequest,
} from '../types/catalog';
import { apiFetch, requestJson } from './http';

export function fetchAdminApps(params: {
  query: string;
  filter: AdminAppFilter;
  sort: SortKey;
  page: number;
  pageSize: number;
}, signal?: AbortSignal): Promise<CatalogResponse> {
  const search = new URLSearchParams();
  if (params.query.trim()) search.set('query', params.query.trim());
  search.set('status', params.filter);
  search.set('sort', params.sort);
  search.set('page', String(params.page));
  search.set('pageSize', String(params.pageSize));
  return requestJson<CatalogResponse>(`/api/v1/admin/apps?${search.toString()}`, { signal });
}

export function createAdminApp(payload: Record<string, unknown>): Promise<AppDetails> {
  return requestJson<AppDetails>('/api/v1/admin/apps', {
    method: 'POST', body: JSON.stringify(payload),
  });
}

export function patchAdminApp(
  appId: string,
  payload: Record<string, unknown>,
): Promise<AppDetails> {
  return requestJson<AppDetails>(`/api/v1/admin/apps/${encodeURIComponent(appId)}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  });
}

export async function deleteAdminApp(appId: string): Promise<void> {
  await requestJson<void>(`/api/v1/admin/apps/${encodeURIComponent(appId)}`, { method: 'DELETE' });
}

export function deleteAllAdminApps(): Promise<{ deleted: number }> {
  return requestJson<{ deleted: number }>('/api/v1/admin/apps?confirm=DELETE_ALL', {
    method: 'DELETE',
  });
}

export async function exportAdminAppsCsv(): Promise<void> {
  const response = await apiFetch('/api/v1/admin/apps/export.csv');
  if (!response.ok) throw new Error(`request_failed_${response.status}`);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'batch-downloader-apps.csv';
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function generateAdminDescription(appId: string): Promise<{ jobId: string; status: string }> {
  return requestJson<{ jobId: string; status: string }>(
    `/api/v1/admin/apps/${encodeURIComponent(appId)}/generate-description`,
    { method: 'POST' },
  );
}

export function createManualInstallerInspection(
  appId: string,
  payload: ManualInstallerInspectionRequest,
): Promise<ManualInstallerInspection> {
  return requestJson<ManualInstallerInspection>(
    `/api/v1/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections`,
    { method: 'POST', body: JSON.stringify(payload) },
  );
}

export function fetchCurrentManualInstallerInspection(
  appId: string,
  signal?: AbortSignal,
): Promise<ManualInstallerInspection> {
  return requestJson<ManualInstallerInspection>(
    `/api/v1/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections/current`,
    { signal },
  );
}

export function fetchManualInstallerInspection(
  appId: string,
  inspectionId: string,
  signal?: AbortSignal,
): Promise<ManualInstallerInspection> {
  return requestJson<ManualInstallerInspection>(
    `/api/v1/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections/${encodeURIComponent(inspectionId)}`,
    { signal },
  );
}

export function applyManualInstallerInspection(
  appId: string,
  inspectionId: string,
  payload: ManualInstallerApplyRequest,
): Promise<ManualInstallerApplyResponse> {
  return requestJson<ManualInstallerApplyResponse>(
    `/api/v1/admin/apps/${encodeURIComponent(appId)}/manual-installer-inspections/${encodeURIComponent(inspectionId)}/apply`,
    { method: 'POST', body: JSON.stringify(payload) },
  );
}

export function createWebsiteAppDiscovery(
  payload: WebsiteAppDiscoveryRequest,
): Promise<WebsiteAppDiscovery> {
  return requestJson<WebsiteAppDiscovery>('/api/v1/admin/app-discoveries', {
    method: 'POST', body: JSON.stringify(payload),
  });
}

export function fetchWebsiteAppDiscovery(
  discoveryId: string,
  signal?: AbortSignal,
): Promise<WebsiteAppDiscovery> {
  return requestJson<WebsiteAppDiscovery>(
    `/api/v1/admin/app-discoveries/${encodeURIComponent(discoveryId)}`,
    { signal },
  );
}

export function applyWebsiteAppDiscovery(
  discoveryId: string,
  payload: WebsiteAppDiscoveryApplyRequest,
): Promise<WebsiteAppDiscoveryApplyResponse> {
  return requestJson<WebsiteAppDiscoveryApplyResponse>(
    `/api/v1/admin/app-discoveries/${encodeURIComponent(discoveryId)}/apply`,
    { method: 'POST', body: JSON.stringify(payload) },
  );
}

export function fetchAbsenceVerificationSummary(): Promise<InstallerAbsenceVerificationSummary> {
  return requestJson('/api/v1/admin/apps/absence-verifications/summary');
}

export function fetchActiveAbsenceVerification(
  appId: string,
): Promise<InstallerAbsenceVerification | null> {
  return requestJson(`/api/v1/admin/apps/${encodeURIComponent(appId)}/absence-verification`);
}

export function confirmInstallerAbsence(
  appId: string,
  payload: InstallerAbsenceVerificationRequest,
): Promise<InstallerAbsenceVerification> {
  return requestJson(`/api/v1/admin/apps/${encodeURIComponent(appId)}/absence-verification`, {
    method: 'POST', body: JSON.stringify(payload),
  });
}
