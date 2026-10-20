import type { ReactNode } from 'react';

/** Marca únicamente fragmentos visibles que coinciden literalmente con la consulta. */
export function LiteralHighlight({ text, query }: Readonly<{ text: string; query?: string }>) {
  const needle = query?.trim();
  if (!needle) return text;

  const lowerText = text.toLowerCase();
  const lowerNeedle = needle.toLowerCase();
  const parts: ReactNode[] = [];
  let from = 0;
  let match = lowerText.indexOf(lowerNeedle, from);
  while (match !== -1) {
    parts.push(text.slice(from, match));
    parts.push(<mark className="literal-search-match" key={match}>{text.slice(match, match + needle.length)}</mark>);
    from = match + needle.length;
    match = lowerText.indexOf(lowerNeedle, from);
  }
  if (!parts.length) return text;
  parts.push(text.slice(from));
  return <>{parts}</>;
}
