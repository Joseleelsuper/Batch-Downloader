import type { ReactNode } from 'react';

interface Props {
  expanded: boolean;
  id: string;
  children: ReactNode;
}

/** Conserva el contenido montado para animar la apertura y el cierre de la ficha. */
export function AppDetailExpander({ expanded, id, children }: Readonly<Props>) {
  return (
    <div className="app-detail-expander" aria-hidden={!expanded}>
      <div className="app-detail-expander-inner" id={id} aria-hidden={!expanded}
        ref={(element) => { if (element) element.inert = !expanded; }}>
        {children}
      </div>
    </div>
  );
}
