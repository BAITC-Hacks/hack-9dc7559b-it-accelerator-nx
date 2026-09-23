import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { SourceView } from '../cart/model';

/** Deliberately small Markdown subset. No HTML, images, arbitrary links or URL guessing.
 * Live source links must come from session-authorized, generated backend DTOs.
 */
export function SafeMarkdown({ text, sources = [] }: { text: string; sources?: readonly SourceView[] }) {
  const allowed = new Set(sources.map((source) => `/sources/${encodeURIComponent(source.key)}`));
  const inline = (line: string): ReactNode[] => {
    const nodes: ReactNode[] = [];
    const pattern = /\[([^\]\n]+)\]\(([^\s)]+)\)|\*\*([^*\n]+)\*\*|`([^`\n]+)`/g;
    let start = 0;
    for (const match of line.matchAll(pattern)) {
      const index = match.index ?? 0;
      nodes.push(line.slice(start, index));
      if (match[1]) nodes.push(allowed.has(match[2])
        ? <Link key={index} to={match[2]}>{match[1]}</Link> : <span key={index}>{match[1]} (ссылка недоступна)</span>);
      else if (match[3]) nodes.push(<strong key={index}>{match[3]}</strong>);
      else nodes.push(<code key={index}>{match[4]}</code>);
      start = index + match[0].length;
    }
    nodes.push(line.slice(start));
    return nodes;
  };
  return <div className="safe-markdown">{text.split(/\n\n+/).map((paragraph, index) => <p key={index}>{inline(paragraph)}</p>)}</div>;
}
