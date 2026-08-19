import { useEffect, useId, useRef, useState } from 'react';

// Renders a Mermaid diagram client-side. Loaded lazily (dynamic import) so the ~600KB mermaid
// bundle only loads when the Architecture page is actually visited, not as part of the main
// bundle every page pays for.
//
// mermaid.initialize()/mermaid.render() share global module state inside the mermaid package.
// When multiple MermaidDiagram instances mount at once (as happens here — the Architecture page
// renders several diagrams in one pass) their render() calls overlap, and one of them silently
// hangs forever (no resolve, no reject) instead of producing an SVG or an error. The fix is to
// serialize all initialize/render calls across every instance through one shared promise chain,
// and to initialize mermaid exactly once module-wide rather than once per instance.
let mermaidInitialized = false;
let renderQueue: Promise<unknown> = Promise.resolve();

function queueMermaidRender(id: string, source: string): Promise<{ svg: string }> {
  const task = renderQueue.then(async () => {
    const mod = await import('mermaid');
    const mermaid = mod.default;
    if (!mermaidInitialized) {
      mermaid.initialize({
        startOnLoad: false,
        theme: window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default',
        securityLevel: 'strict',
      });
      mermaidInitialized = true;
    }
    return mermaid.render(id, source);
  });
  // Keep the queue moving even if this render fails — swallow here, real error still propagates
  // to the caller via `task`.
  renderQueue = task.catch(() => undefined);
  return task;
}

export function MermaidDiagram({ source }: { source: string }) {
  const id = useId().replace(/:/g, '-');
  const containerRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    queueMermaidRender(`mermaid-${id}`, source)
      .then(({ svg }) => {
        if (!cancelled && containerRef.current) {
          containerRef.current.innerHTML = svg;
        }
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to render diagram');
      });

    return () => {
      cancelled = true;
    };
  }, [id, source]);

  if (error) {
    return (
      <div className="mermaid-fallback">
        <p className="error">Diagram could not be rendered ({error}). Raw source:</p>
        <pre>{source}</pre>
      </div>
    );
  }

  return <div className="mermaid-diagram" ref={containerRef} />;
}
