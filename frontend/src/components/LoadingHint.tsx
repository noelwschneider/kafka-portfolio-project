interface Props {
  label: string;
}

// Shared loading affordance for `useQuery` calls across pages (issue #11). Every page previously
// wrote its own ad hoc `{isLoading && <p className="hint">Loading X…</p>}`; this is that same
// markup pulled into one place so the pattern stays consistent as more pages/queries are added.
export function LoadingHint({ label }: Props) {
  return <p className="hint">{label}</p>;
}
