import { type FormEvent, useState } from 'react';
import {
  createSoftwareRequest, fetchAdminSoftwareRequests, fetchOwnSoftwareRequests, reviewSoftwareRequest,
  type SoftwareRequest, type SoftwareRequestStatus,
} from '../api/softwareRequests';
import { ApiRequestError } from '../api/http';
import { useAuth } from '../auth/AuthContext';
import { AsyncContent } from '../components/AsyncContent';
import { Pagination } from '../components/Pagination';
import { useAsyncResource } from '../hooks/useAsyncResource';
import { useTranslation, type Translator } from '../services/i18n';
import { formatDate } from '../utils/date';

function requestError(t: Translator, cause: unknown) {
  const key = cause instanceof ApiRequestError ? `software.requests.error.${cause.code}` : '';
  const translated = key ? t(key) : '';
  return translated && translated !== key ? translated : t('software.requests.failed');
}

export function SoftwareRequestsPage({ admin = false }: Readonly<{ admin?: boolean }>) {
  const t = useTranslation();
  const { account } = useAuth();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(12);
  const [status, setStatus] = useState<SoftwareRequestStatus | 'all'>('pending');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const resource = useAsyncResource((signal) => admin
    ? fetchAdminSoftwareRequests(page, pageSize, status === 'all' ? undefined : status, signal)
    : fetchOwnSoftwareRequests(page, pageSize, signal),
  { scope: `${account?.id}:${admin}:${page}:${pageSize}:${status}` });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true); setMessage(null); setError(null);
    try {
      await createSoftwareRequest(name.trim(), url.trim());
      setName(''); setUrl(''); setPage(1); resource.refresh();
      setMessage(t('software.requests.saved'));
    } catch (cause) { setError(requestError(t, cause)); }
    finally { setSubmitting(false); }
  }

  return <section className={`${admin ? 'admin-panel' : 'account-page'} software-requests`}>
    <h1>{t('software.requests.title')}</h1>
    <p>{t(admin ? 'software.requests.adminHelp' : 'software.requests.help')}</p>
    {admin ? <label>{t('software.requests.filter')}
      <select value={status} onChange={(event) => { setStatus(event.target.value as typeof status); setPage(1); }}>
        {(['pending', 'accepted', 'rejected', 'all'] as const).map((value) =>
          <option key={value} value={value}>{t(`software.requests.status.${value}`)}</option>)}
      </select>
    </label> : <form className="account-card bundle-fields" onSubmit={(event) => void submit(event)}>
      <label>{t('software.requests.name')}<input required maxLength={160} value={name}
        onChange={(event) => setName(event.target.value)} /></label>
      <label>{t('software.requests.url')}<input required type="url" maxLength={2048} value={url}
        placeholder="https://" onChange={(event) => setUrl(event.target.value)} /></label>
      <button className="primary-button" type="submit" disabled={submitting}>{t('software.requests.submit')}</button>
    </form>}
    {message ? <p role="status">{message}</p> : null}
    {error ? <p className="error-banner" role="alert">{error}</p> : null}
    <AsyncContent {...resource} hasData={resource.data !== undefined} onRetry={resource.refresh}>
      {resource.data?.data.length === 0 ? <p className="empty-state">{t('software.requests.empty')}</p> : null}
      <ul className="account-list">{resource.data?.data.map((request) => <li key={`${request.id}:${request.version}`}>
        <article className="account-card">
          <h2>{request.name}</h2>
          <p><a href={request.officialUrl} target="_blank" rel="noopener noreferrer">{request.officialUrl}</a></p>
          <p>{t(`software.requests.status.${request.status}`)} · {formatDate(request.createdAt)}</p>
          {request.reviewNote ? <p>{request.reviewNote}</p> : null}
          {admin ? <RequestReview request={request} onSaved={resource.refresh} /> : null}
        </article>
      </li>)}</ul>
      {resource.data ? <Pagination page={page} pageSize={pageSize} total={resource.data.total}
        onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(1); }} /> : null}
    </AsyncContent>
  </section>;
}

function RequestReview({ request, onSaved }: Readonly<{ request: SoftwareRequest; onSaved: () => void }>) {
  const t = useTranslation();
  const [status, setStatus] = useState<'accepted' | 'rejected'>(request.status === 'rejected' ? 'rejected' : 'accepted');
  const [note, setNote] = useState(request.reviewNote);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true); setError(null);
    try { await reviewSoftwareRequest(request.id, status, note.trim(), request.version); onSaved(); }
    catch (cause) { setError(requestError(t, cause)); }
    finally { setSubmitting(false); }
  }
  return <form className="bundle-fields" onSubmit={(event) => void submit(event)}>
    <label>{t('software.requests.decision')}<select value={status}
      onChange={(event) => setStatus(event.target.value as typeof status)}>
      <option value="accepted">{t('software.requests.status.accepted')}</option>
      <option value="rejected">{t('software.requests.status.rejected')}</option>
    </select></label>
    <label>{t('software.requests.note')}<textarea required maxLength={1000} value={note}
      onChange={(event) => setNote(event.target.value)} /></label>
    <button className="primary-button" type="submit" disabled={submitting}>{t('software.requests.review')}</button>
    {error ? <p role="alert">{error}</p> : null}
  </form>;
}
