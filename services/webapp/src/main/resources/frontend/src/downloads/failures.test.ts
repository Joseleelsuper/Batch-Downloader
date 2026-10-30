import { describe, expect, it } from 'vitest';
import english from '@batch-locales/en/downloads.json';
import spanish from '@batch-locales/es/downloads.json';
import template from '@batch-locales/template/downloads.json';
import { t, type Translator } from '../services/i18n';
import { ApiRequestError } from '../api/http';
import { actionFailure, explainFailure, isDownloadActionError } from './failures';

describe('download failure explanations', () => {
  it('keeps HTTP 618 factual without assuming a signed link expired', () => {
    const result = explainFailure(t, 'source_revalidation_http_618');
    expect(result.httpStatus).toBe(618);
    expect(result.what).toContain('comprobar el instalador');
    expect(result.cause).toContain('HTTP 618');
    expect(result.cause).not.toMatch(/caduc|expir|firma/i);
    expect(result.recovery).toContain('página oficial');
  });

  it.each([
    ['source_sha256_mismatch', 'integridad'],
    ['source_revalidation_installer_signature_mismatch', 'integridad'],
    ['source_revalidation_html_response', 'instalador admitido'],
    ['source_revalidation_timeout', 'tiempo de espera'],
    ['source_revalidation_network_error', 'conexión'],
    ['dns_resolution_failed', 'conexión'],
    ['too_many_redirects', 'enlace de descarga'],
    ['zip_creation_failed', 'preparar el ZIP'],
    ['manifest_creation_failed', 'preparar el ZIP'],
    ['minio_upload_failed', 'almacenamiento'],
    ['local_io_error', 'almacenamiento'],
    ['file_size_limit_exceeded', 'límite'],
  ])('explains %s with the specific category', (code, message) => {
    expect(explainFailure(t, code).what).toContain(message);
  });

  it('falls back honestly for legacy, absent and unknown causes', () => {
    expect(explainFailure(t, 'source_resolver_unavailable').cause).toContain('causa más precisa');
    expect(explainFailure(t, 'future_failure').cause).toContain('información suficiente');
    expect(explainFailure(t, null).code).toBe('unknown_error');
    expect(explainFailure(t, 'https://private.test?sig=secret').code).toBe('unknown_error');
    expect(JSON.stringify(actionFailure(new Error('private_token')))).not.toContain('private_token');
  });

  it('distinguishes a resolver rejection from a provider access requirement', () => {
    const internal = explainFailure(t, 'source_resolution_http_401');
    expect(internal.what).toContain('servicio de resolución');
    expect(internal.recovery).not.toContain('requerir acceso');
    expect(explainFailure(t, 'remote_http_403').recovery).toContain('requerir acceso');
  });

  it('keeps an API code and HTTP status while distinguishing a saved ZIP receipt', () => {
    const error = actionFailure(new ApiRequestError(503, 'download_unavailable'));
    expect(error).toEqual({ code: 'download_unavailable', httpStatus: 503, operation: 'delivery' });
    expect(explainFailure(t, error.code, error.httpStatus).httpStatus).toBe(503);
    expect(explainFailure(t, error.code, error.httpStatus, 'receipt').cause).toContain('ya está guardado');
    expect(isDownloadActionError({ ...error, httpStatus: '503' })).toBe(false);
    expect(isDownloadActionError({ ...error, code: 'https://secret' })).toBe(false);
    expect(isDownloadActionError(error)).toBe(true);
  });

  it('provides matching locale keys and a complete English explanation', () => {
    expect(Object.keys(english).sort()).toEqual(Object.keys(spanish).sort());
    expect(Object.keys(template).sort()).toEqual(Object.keys(spanish).sort());
    const translate: Translator = (key, params) => {
      let value = (english as Record<string, string>)[key] ?? key;
      for (const [name, replacement] of Object.entries(params ?? {})) {
        value = value.split(`{${name}}`).join(String(replacement));
      }
      return value;
    };
    const explanation = explainFailure(translate, 'source_revalidation_http_618');
    expect(explanation.what).toBe('The installer could not be checked with the provider.');
    expect(explanation.cause).toContain('HTTP 618');
    expect(explanation.recovery).toContain('official website');
  });
});
