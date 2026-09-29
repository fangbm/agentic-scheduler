# Temvio Phase 1 brand kit

Approved display spelling: **Temvio**. The owner selected these supplied raster masters on 2026-09-29.

| Asset | Source | Intended use |
| --- | --- | --- |
| Icon | `temvio-icon-transparent.png` | Android/Wear launcher presentation, favicon/social crops, square contexts |
| Horizontal wordmark | `temvio-wordmark-transparent.png` | README, social and horizontal presentation |

## Presentation rules

- Preserve the supplied transparent masters; do not redraw, crop into the mark, or add small decorative overlays.
- Keep a clear space of at least one icon-grid unit around the icon, and at least the wordmark's dot diameter around the horizontal mark.
- Use the full-colour masters on light or neutral surfaces. For a dark/reversed treatment, use an approved export rather than automatically inverting the artwork.
- The supplied `apps/*/res/drawable/temvio_icon.png` copies are presentation-only launcher resources. Existing package names, application IDs, theme names, database files, credentials, encrypted data, Sync wire identifiers and module names are intentionally unchanged.

## Rollback mapping

| New presentation resource | Previous presentation |
| --- | --- |
| Android launcher label `Temvio` | `Agentic Scheduler` |
| Wear launcher label `Temvio` | `Agentic Scheduler` |
| Android/Wear `temvio_icon.png` | no explicit manifest icon resource |

The original supplied masters are retained here alongside the in-app copies. Reverting the Phase 1 commit restores the previous display labels without modifying stored data or technical identities.
