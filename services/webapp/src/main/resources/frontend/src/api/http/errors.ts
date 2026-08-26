export class ApiRequestError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly retryAfter: string | null = null,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(code);
    this.name = 'ApiRequestError';
  }
}

/** Convierte el formato de error de Core y FastAPI a un contrato único de UI. */
export async function responseError(response: Response): Promise<ApiRequestError> {
  const payload = await response.json().catch(() => null) as {
    code?: string;
    detail?: { code?: string } | string;
    details?: Record<string, unknown>;
  } | null;
  const detailCode = typeof payload?.detail === 'object' ? payload.detail.code : undefined;
  return new ApiRequestError(
    response.status,
    payload?.code ?? detailCode ?? `request_failed_${response.status}`,
    response.headers.get('Retry-After'),
    payload?.details ?? {},
  );
}
