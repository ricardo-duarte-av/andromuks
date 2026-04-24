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

## Known Limitation

If a message interleaves text and tables (text → table → more text), the non-table text nodes are all rendered together above the table cards. The relative ordering of text-after-table is lost. This is acceptable for typical Matrix messages where tables are at the end or occupy the whole message body.
