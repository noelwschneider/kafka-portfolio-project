import type { ServiceKey } from '../lib/scenarioFlow';

// Plain inline SVG, no icon library dependency — matches this app's existing icon-free,
// text-and-color-driven visual style (see frontend/STYLE_GUIDE.md). One glyph per backend service,
// candidate shapes taken from issue #57: cart (order), dollar sign (payment), stacked boxes
// (inventory), truck (fulfillment). Color comes from the caller via `currentColor` (the
// `.timeline-service-badge.service-*` CSS rules in index.css set it from the matching
// `--color-service-*` token) rather than being hardcoded here.
export function ServiceIcon({ service }: { service: ServiceKey }) {
  switch (service) {
    case 'order':
      return (
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <path
            d="M1.5 2h1.5l1.5 8h7l1.5-5h-9"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle cx="6" cy="13" r="1" fill="currentColor" />
          <circle cx="11" cy="13" r="1" fill="currentColor" />
        </svg>
      );
    case 'payment':
      return (
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <text
            x="8"
            y="11"
            textAnchor="middle"
            fontSize="8"
            fontWeight="700"
            fill="currentColor"
            fontFamily="ui-monospace, 'SF Mono', Menlo, monospace"
          >
            $
          </text>
        </svg>
      );
    case 'inventory':
      return (
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <rect x="1.5" y="6" width="6" height="6" rx="0.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <rect x="8.5" y="3.5" width="6" height="6" rx="0.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
        </svg>
      );
    case 'fulfillment':
      return (
        <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
          <rect x="1" y="5" width="8" height="6" rx="0.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M9 7h3l2 2v2H9V7z" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
          <circle cx="4" cy="12.5" r="1.2" fill="currentColor" />
          <circle cx="11.5" cy="12.5" r="1.2" fill="currentColor" />
        </svg>
      );
  }
}
