import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ContentBoundary } from '../routing/ContentBoundary';
import { LoadingPlaceholder } from './AsyncContent';

/** Anticipa el contenido cercano y lo conserva montado después de la primera activación. */
export function DeferredSection({ children, fallback }: Readonly<{ children: ReactNode; fallback?: ReactNode }>) {
  const [active, setActive] = useState(() => typeof IntersectionObserver === 'undefined');
  const target = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (active || !target.current) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setActive(true);
        observer.disconnect();
      }
    }, { rootMargin: '300px', threshold: 0 });
    observer.observe(target.current);
    return () => observer.disconnect();
  }, [active]);
  return <div ref={target} className="deferred-section">
    {active ? <ContentBoundary>{children}</ContentBoundary> : fallback ?? <LoadingPlaceholder />}
  </div>;
}
