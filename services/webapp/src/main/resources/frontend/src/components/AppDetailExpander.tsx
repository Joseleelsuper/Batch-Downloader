import { useEffect, useState, type ReactNode } from 'react';
import { ContentBoundary } from '../routing/ContentBoundary';

interface Props {
  expanded: boolean;
  id: string;
  children: ReactNode;
}

/** Conserva el contenido montado para animar la apertura y el cierre de la ficha. */
export function AppDetailExpander({ expanded, id, children }: Readonly<Props>) {
  const [opened, setOpened] = useState(expanded);
  useEffect(() => { if (expanded) setOpened(true); }, [expanded]);
  return (
    <div className="app-detail-expander" aria-hidden={!expanded}>
      <div className="app-detail-expander-inner" id={id} aria-hidden={!expanded}
        ref={(element) => { if (element) element.inert = !expanded; }}>
        {expanded || opened ? <ContentBoundary>{children}</ContentBoundary> : null}
      </div>
    </div>
  );
}
