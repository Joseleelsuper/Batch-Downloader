import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppFilters } from './AppFilters';
import type { FilterKey } from '../types/catalog';

const counts: Record<FilterKey, number> = {
  all: 10,
  available: 8,
  review: 1,
  missing: 1,
};

describe('AppFilters', () => {
  afterEach(() => cleanup());

  it('renders facet links and active facet chips', () => {
    const onRemoveTag = vi.fn();
    const onRemovePublisher = vi.fn();
    render(
      <MemoryRouter>
        <AppFilters
          active="all"
          counts={counts}
          selectedTags={['.NET', 'runtime']}
          selectedPublishers={['ACME, Inc.']}
          tagMatchMin={1}
          catalogSearch="tag=.NET&publisher=ACME%2C+Inc."
          onChange={vi.fn()}
          onRemoveTag={onRemoveTag}
          onRemovePublisher={onRemovePublisher}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /Tags/ })).toHaveAttribute(
      'href',
      '/catalog/tags?tag=.NET&publisher=ACME%2C+Inc.',
    );
    expect(screen.getByText('1 de 2 tags')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '.NET' }));
    fireEvent.click(screen.getByRole('button', { name: 'ACME, Inc.' }));

    expect(onRemoveTag).toHaveBeenCalledWith('.NET');
    expect(onRemovePublisher).toHaveBeenCalledWith('ACME, Inc.');
  });
});
