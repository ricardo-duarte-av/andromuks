# HTML Rendering (`utils/html.kt`, `utils/HtmlTableRenderer.kt`)

## Overview

`HtmlMessageText` is the main composable for rendering Matrix `formatted_body` HTML. It parses HTML into a tree of `HtmlNode` via `HtmlParser`, builds an `AnnotatedString` from non-table nodes, and renders it in a `Text()` with custom gesture handling for links, spoilers, and code blocks.

## Table Rendering

`<table>` nodes are extracted from the parsed tree before the `AnnotatedString` is built (`tableNodes` / `nonTableNodes` split). Each table is rendered as a tappable `HtmlTablePreviewCard` (shows row/column count + column header preview).

Tapping opens `HtmlTableDialog` — a full-screen dialog with `HtmlTableContent`:
- `LazyColumn` (vertical scroll) wrapped in `horizontalScroll`
- Auto-computed column widths (clamped 80–220 dp)
- Alternating row colors and column dividers

Parsing logic lives in `parseTableNode()` in `HtmlTableRenderer.kt`.

## Maths (MSC2191)

gomuks delivers MSC2191 maths in `local_content.sanitized_html` as
`<hicli-math displaymode="inline|block" latex="…"><code>…</code></hicli-math>`. The raw
LaTeX is in the `latex` attribute (the inner `<code>` is just a textual fallback).

- `hicli-math` is in `ALLOWED_HTML_TAGS` so the parser keeps the node. `extractMathLatex()`
  reads the `latex` attribute (entity-decoded, since `sanitized_html` is not decoded upstream
  and LaTeX can contain `&`/`<`/`>`), falling back to the `<code>` text.
- **Inline** maths (`displaymode="inline"`, the common case) are registered on the existing
  inline-image path (`InlineImageData.latex != null`) and rendered as `InlineTextContent`,
  baseline-centered, sized to the body text. No new map is threaded through the append
  functions — math reuses `inlineImages`.
- **Block** maths (`displaymode` anything but `inline`) that sit at the top level of the node
  tree are pulled out (like `<table>`) into `blockMathLatex` and rendered by `BlockMath()` —
  centered on their own line, in a `horizontalScroll` so wide formulae (e.g. the quadratic
  formula) aren't clipped, slightly larger than body text. Block maths nested deeper than top
  level fall back to inline rendering.
- Rendering is done natively by **JLaTeXMath** (`ru.noties:jlatexmath-android`) to a `Drawable`
  (no WebView). `LatexDrawableImage` draws it via `Canvas`/`nativeCanvas`, tinted to the text
  colour (theme-aware). Malformed LaTeX falls back to the monospace source string so a bad
  expression never blanks the message. ProGuard keeps `ru.noties.jlatexmath.**` and
  `org.scilab.forge.jlatexmath.**` (reflective font/symbol loading).

## Known Limitation

If a message interleaves text and tables (text → table → more text), the non-table text nodes are all rendered together above the table cards. The relative ordering of text-after-table is lost. This is acceptable for typical Matrix messages where tables are at the end or occupy the whole message body.
