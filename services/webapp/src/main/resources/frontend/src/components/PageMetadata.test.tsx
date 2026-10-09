import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../api/http';
import { PageMetadata, type SeoMetadata } from './PageMetadata';

vi.mock('../api/http', () => ({ apiFetch: vi.fn() }));

const metadata: SeoMetadata = {
  title: 'Steam | Batch Downloader', description: 'Juegos y aplicaciones',
  canonicalUrl: 'https://batchdownloader.dev/catalog/app/steam',
  imageUrl: 'https://batchdownloader.dev/social/card.png?path=steam',
  imageAlt: 'Steam', robots: 'index, follow', type: 'article',
  structuredData: { '@type': 'SoftwareApplication', name: 'Steam </script><!--#' },
};
const response = (value = metadata, status = 200) => new Response(JSON.stringify(value), { status });

function renderPage(path = '/catalog/app/steam') {
  return render(<MemoryRouter initialEntries={[path]}>
    <PageMetadata />
    <Link to="/privacy">Privacidad</Link>
    <Link to="/login?token=secret">Acceso</Link>
    <Link to="/catalog/app/steam?searchMode=semantic">Modo de búsqueda</Link>
  </MemoryRouter>);
}

describe('page metadata', () => {
  beforeEach(() => { document.head.innerHTML = ''; vi.mocked(apiFetch).mockReset(); });
  afterEach(cleanup);

  it('updates initial and client navigation metadata without duplicate tags', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(response()).mockResolvedValueOnce(response({
      ...metadata, title: 'Privacidad', canonicalUrl: 'https://batchdownloader.dev/privacy',
    }));
    renderPage();
    await waitFor(() => expect(document.title).toBe(metadata.title));
    expect(document.querySelector('meta[property="og:image"]')).toHaveAttribute('content', metadata.imageUrl);
    expect(document.querySelector('meta[name="twitter:card"]')).toHaveAttribute('content', 'summary_large_image');
    expect(document.querySelector('script[type="application/ld+json"]')?.textContent).not.toContain('</script>');
    fireEvent.click(screen.getByText('Privacidad'));
    await waitFor(() => expect(document.title).toBe('Privacidad'));
    expect(document.querySelectorAll('meta[property="og:title"]')).toHaveLength(1);
    expect(document.querySelector('link[rel="canonical"]')).toHaveAttribute('href', 'https://batchdownloader.dev/privacy');
  });

  it('clears public tags on private navigation and never sends login tokens', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response());
    renderPage();
    await waitFor(() => expect(document.title).toBe(metadata.title));
    fireEvent.click(screen.getByText('Acceso'));
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute('content', 'noindex, follow');
    expect(document.querySelector('meta[property="og:image"]')).toBeNull();
    expect(document.querySelector('script[type="application/ld+json"]')).toBeNull();
    expect(apiFetch).toHaveBeenCalledTimes(1);
  });

  it('ignores stale responses and applies safe metadata on a 404', async () => {
    let finish!: (result: Response) => void;
    vi.mocked(apiFetch).mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }))
      .mockResolvedValueOnce(response({ ...metadata, title: 'No encontrada', robots: 'noindex, follow', imageUrl: null }, 404));
    renderPage();
    fireEvent.click(screen.getByText('Privacidad'));
    await waitFor(() => expect(document.title).toBe('No encontrada'));
    await act(async () => { finish(response()); });
    expect(document.title).toBe('No encontrada');
    expect(document.querySelector('meta[property="og:image"]')).toBeNull();
  });

  it('preserves the server head if its initial metadata request fails', async () => {
    document.head.innerHTML = '<title>Servidor</title><meta property="og:url" content="https://batchdownloader.dev/privacy"><meta name="robots" content="index, follow">';
    vi.mocked(apiFetch).mockRejectedValue(new Error('offline'));
    renderPage('/privacy');
    await act(async () => {});
    expect(document.title).toBe('Servidor');
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute('content', 'index, follow');
  });

  it('keeps the indexable server head during public query normalization when metadata is delayed', () => {
    document.head.innerHTML = '<title>Steam</title><meta property="og:url" content="https://batchdownloader.dev/catalog/app/steam"><meta name="robots" content="index, follow"><link rel="canonical" href="https://batchdownloader.dev/catalog/app/steam">';
    vi.mocked(apiFetch).mockImplementation(() => new Promise(() => {}));
    renderPage();
    fireEvent.click(screen.getByText('Modo de búsqueda'));
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute('content', 'index, follow');
    expect(document.querySelector('link[rel="canonical"]')).toHaveAttribute('href', metadata.canonicalUrl);
  });
});
