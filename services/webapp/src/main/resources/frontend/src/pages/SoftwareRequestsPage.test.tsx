import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as requests from '../api/softwareRequests';
import { ApiRequestError } from '../api/http';
import { SoftwareRequestsPage } from './SoftwareRequestsPage';

const auth = vi.hoisted(() => ({ account: { id: 'owner-a' } }));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }));
const request: requests.SoftwareRequest = {
  id: 'request-1', name: 'New Editor', officialUrl: 'https://example.org/', status: 'pending',
  reviewNote: '', createdAt: '2026-11-05T12:00:00Z', reviewedAt: null, version: 0,
};
const page = { data: [request], page: 1, pageSize: 12, total: 1 };

beforeEach(() => {
  auth.account = { id: 'owner-a' };
  vi.spyOn(requests, 'fetchOwnSoftwareRequests').mockResolvedValue(page);
  vi.spyOn(requests, 'fetchAdminSoftwareRequests').mockResolvedValue(page);
  vi.spyOn(requests, 'createSoftwareRequest').mockResolvedValue(request);
  vi.spyOn(requests, 'cancelSoftwareRequest').mockResolvedValue(undefined);
  vi.spyOn(requests, 'reviewSoftwareRequest').mockResolvedValue({ ...request, status: 'accepted', version: 1 });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('software requests', () => {
  it('requires name and official URL and reloads the personal history after submission', async () => {
    render(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    const name = screen.getByLabelText('Nombre de la aplicación');
    const url = screen.getByLabelText('Página oficial HTTPS');
    expect(name).toBeRequired(); expect(url).toBeRequired();
    expect(url).toHaveAttribute('type', 'url');
    fireEvent.change(name, { target: { value: ' New Editor ' } });
    fireEvent.change(url, { target: { value: 'https://example.org/' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar solicitud' }));
    expect(await screen.findByText('Solicitud enviada para revisión.')).toHaveAttribute('role', 'status');
    expect(requests.createSoftwareRequest).toHaveBeenCalledWith('New Editor', 'https://example.org/');
    expect(await screen.findByRole('heading', { name: 'New Editor' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'https://example.org/' })).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.queryByRole('button', { name: 'Guardar revisión' })).not.toBeInTheDocument();
  });

  it('shows duplicate feedback without reporting a successful submission', async () => {
    vi.mocked(requests.createSoftwareRequest).mockRejectedValue(new ApiRequestError(409, 'software_request_duplicate'));
    render(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Nombre de la aplicación'), { target: { value: 'New Editor' } });
    fireEvent.change(screen.getByLabelText('Página oficial HTTPS'), { target: { value: 'https://example.org/' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar solicitud' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Ya has solicitado esta aplicación');
    expect(screen.queryByText('Solicitud enviada para revisión.')).not.toBeInTheDocument();
  });

  it('requires a review reason and submits the visible version without importing software', async () => {
    render(<MemoryRouter><SoftwareRequestsPage admin /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'New Editor' })).toBeInTheDocument();
    const note = screen.getByLabelText('Motivo de la revisión');
    expect(note).toBeRequired();
    fireEvent.change(note, { target: { value: ' Fuente oficial revisada ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar revisión' }));
    await waitFor(() => expect(requests.reviewSoftwareRequest).toHaveBeenCalledWith('request-1', 'accepted', 'Fuente oficial revisada', 0));
    expect(requests.createSoftwareRequest).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Enviar solicitud' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancelar solicitud' })).not.toBeInTheDocument();
    await waitFor(() => expect(requests.fetchAdminSoftwareRequests).toHaveBeenCalledTimes(2));
  });

  it('keeps a stale review editable and explains the conflict', async () => {
    vi.mocked(requests.reviewSoftwareRequest).mockRejectedValue(new ApiRequestError(409, 'software_request_changed'));
    render(<MemoryRouter><SoftwareRequestsPage admin /></MemoryRouter>);
    fireEvent.change(await screen.findByLabelText('Motivo de la revisión'), { target: { value: 'Motivo' } });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar revisión' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Otra revisión ha cambiado esta solicitud');
    expect(screen.getByRole('button', { name: 'Guardar revisión' })).toBeEnabled();
  });

  it('hides a previous account history while loading a new scope', async () => {
    const view = render(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    await screen.findByRole('heading', { name: 'New Editor' });
    auth.account = { id: 'owner-b' };
    vi.mocked(requests.fetchOwnSoftwareRequests).mockReturnValue(new Promise(() => {}));
    view.rerender(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    expect(screen.queryByRole('heading', { name: 'New Editor' })).not.toBeInTheDocument();
  });

  it('asks for confirmation and removes a cancelled pending request from the history', async () => {
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(false);
    vi.mocked(requests.cancelSoftwareRequest).mockImplementation(async () => {
      vi.mocked(requests.fetchOwnSoftwareRequests).mockResolvedValue({ ...page, data: [], total: 0 });
    });
    render(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    const cancel = await screen.findByRole('button', { name: 'Cancelar solicitud' });
    fireEvent.click(cancel);
    expect(requests.cancelSoftwareRequest).not.toHaveBeenCalled();
    confirmation.mockReturnValue(true);
    fireEvent.click(cancel);
    expect(await screen.findByText('Solicitud cancelada y eliminada del historial.')).toHaveAttribute('role', 'status');
    expect(requests.cancelSoftwareRequest).toHaveBeenCalledExactlyOnceWith('request-1');
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'New Editor' })).not.toBeInTheDocument());
  });

  it('keeps the request visible and explains a cancellation blocked by a concurrent review', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    vi.mocked(requests.cancelSoftwareRequest).mockRejectedValue(new ApiRequestError(409, 'software_request_not_pending'));
    render(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancelar solicitud' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('La solicitud ya ha sido revisada');
    expect(screen.getByRole('heading', { name: 'New Editor' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancelar solicitud' })).toBeEnabled();
  });

  it.each(['accepted', 'rejected'] as const)('does not offer cancellation for a %s request', async (status) => {
    vi.mocked(requests.fetchOwnSoftwareRequests).mockResolvedValue({ ...page, data: [{ ...request, status }] });
    render(<MemoryRouter><SoftwareRequestsPage /></MemoryRouter>);
    await screen.findByRole('heading', { name: 'New Editor' });
    expect(screen.queryByRole('button', { name: 'Cancelar solicitud' })).not.toBeInTheDocument();
  });
});
