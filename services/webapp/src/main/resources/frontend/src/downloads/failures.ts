import { ApiRequestError } from '../api/http';
import type { Translator } from '../services/i18n';

export interface DownloadActionError {
  code: string;
  httpStatus?: number;
  operation: 'delivery' | 'receipt' | 'cancel';
}

const CODE_PATTERN = /^[a-z][a-z0-9_]{0,79}$/;
const HTTP_PATTERN = /^(source_revalidation|source_resolution|remote)_http_([1-9][0-9]{2})$/;
const LOCAL_FAILURE_CODES = new Set([
  'download_size_unavailable', 'download_size_mismatch', 'download_range_mismatch',
  'download_body_missing', 'download_link_failed', 'download_receipt_failed',
  'download_cancel_failed',
]);

export function safeFailureCode(value: unknown): string {
  return typeof value === 'string' && CODE_PATTERN.test(value) ? value : 'unknown_error';
}

export function isDownloadActionError(value: unknown): value is DownloadActionError {
  if (!value || typeof value !== 'object') return false;
  const error = value as Partial<DownloadActionError>;
  return typeof error.code === 'string' && CODE_PATTERN.test(error.code)
    && ['delivery', 'receipt', 'cancel'].includes(error.operation ?? '')
    && (error.httpStatus == null || (Number.isInteger(error.httpStatus)
      && error.httpStatus >= 100 && error.httpStatus <= 999));
}

export function actionFailure(
  cause: unknown, operation: DownloadActionError['operation'] = 'delivery',
): DownloadActionError {
  const fallback = operation === 'receipt' ? 'download_receipt_failed'
    : operation === 'cancel' ? 'download_cancel_failed' : 'download_link_failed';
  if (cause instanceof ApiRequestError) {
    return { code: safeFailureCode(cause.code), httpStatus: cause.status, operation };
  }
  if (cause instanceof DOMException && cause.name === 'NotAllowedError') {
    return { code: 'destination_permission_denied', operation };
  }
  return { code: cause instanceof Error && LOCAL_FAILURE_CODES.has(cause.message)
    ? cause.message : fallback, operation };
}

const EXACT_KINDS: Readonly<Record<string, string>> = {
  source_not_found: 'sourceMissing',
  source_not_verified: 'sourceUnverified',
  source_url_unreadable: 'sourceUnverified',
  source_resolver_unavailable: 'resolver',
  invalid_source_resolution_response: 'resolver',
  source_revalidation_changed: 'sourceChanged',
  source_sha256_mismatch: 'integrity',
  installer_signature_mismatch: 'integrity',
  installer_content_type_mismatch: 'content',
  unexpected_download_content_type: 'content',
  encoded_response_not_supported: 'content',
  html_response: 'content',
  missing_installer_extension: 'content',
  not_an_installer: 'content',
  unsupported_installer_format: 'content',
  non_binary_installer_reference: 'content',
  github_source_archive: 'content',
  github_zip_not_release_asset: 'content',
  file_too_large: 'limit',
  local_io_error: 'storage',
  download_signing_unavailable: 'signing',
  download_job_not_found: 'missingZip',
  download_not_ready: 'missingZip',
  destination_permission_denied: 'permission',
  download_receipt_failed: 'receipt',
  download_link_failed: 'delivery',
  download_cancel_failed: 'cancel',
  download_size_unavailable: 'sizeUnknown',
  download_size_mismatch: 'integrity',
  download_range_mismatch: 'integrity',
  download_body_missing: 'delivery',
  invalid_job_size: 'limit',
  invalid_source_selection: 'sourceUnverified',
  selected_source_unavailable: 'sourceUnverified',
  no_downloadable_apps: 'sourceUnverified',
  all_items_failed: 'allFailed',
  all_downloads_failed: 'allFailed',
  connection_lost: 'connection',
};

function failureKind(code: string): string {
  const base = code.replace(/^source_revalidation_/, '');
  const exact = EXACT_KINDS[code] ?? EXACT_KINDS[base];
  if (exact) return exact;
  if (/(sha256|checksum|signature|integrity)/.test(code)) return 'integrity';
  if (/(size_limit|too_many_items|budget|limit_exceeded|rate_limit)/.test(code)) return 'limit';
  if (/(zip_|manifest_|archive_)/.test(code)) return 'packaging';
  if (/(storage|disk|temp_directory|minio|cleanup_failed)/.test(code)) return 'storage';
  if (/(timeout|timed_out)/.test(code)) return 'timeout';
  if (/(dns_not_public|credentials_forbidden|not_https|unsupported_scheme|invalid_url|missing_domain)/.test(code)) return 'security';
  if (/(redirect)/.test(code)) return 'redirect';
  if (/(network|dns|remote_io|no_response|encoded_response)/.test(code)) return 'network';
  if (code.startsWith('source_')) return 'sourceUnverified';
  return 'generic';
}

export function explainFailure(t: Translator, code?: string | null,
  httpStatus?: number, operation?: DownloadActionError['operation']) {
  const safeCode = safeFailureCode(code);
  const http = HTTP_PATTERN.exec(safeCode);
  const status = http ? Number(http[2]) : httpStatus;
  if (http && operation !== 'receipt') {
    const phase = http[1] === 'source_revalidation' ? 'httpRevalidation'
      : http[1] === 'source_resolution' ? 'httpResolution' : 'httpDownload';
    const response = [401, 403].includes(Number(http[2])) ? 'httpDenied'
      : http[2] === '404' ? 'httpMissing' : http[2] === '429' ? 'httpBusy' : 'httpOther';
    return {
      code: safeCode, httpStatus: status,
      what: t(`download.job.issue.${phase}.what`),
      cause: t(`download.job.issue.${response}.cause`, { status: Number(http[2]) }),
      recovery: t(`download.job.issue.${phase === 'httpResolution' ? 'resolver' : response}.recovery`),
    };
  }
  const kind = operation === 'receipt' ? 'receipt' : failureKind(safeCode);
  const apiKey = `download.job.apiError.${safeCode}`;
  const apiMessage = t(apiKey);
  return {
    code: safeCode, httpStatus: status,
    what: operation !== 'receipt' && apiMessage !== apiKey
      ? apiMessage : t(`download.job.issue.${kind}.what`),
    cause: t(`download.job.issue.${kind}.cause`),
    recovery: t(`download.job.issue.${kind}.recovery`),
  };
}
