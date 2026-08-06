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

## Inline Images (`<img>`)

Every `<img>` becomes an `InlineTextContent` placeholder inside the `AnnotatedString`, so images
flow with the text rather than sitting in their own block. By default the placeholder is a square
clamped to the height of one line of text (`minOf(declared height, textLineHeight)`, doubled for
emoji-only messages) — that is what makes custom emoticons sit on the baseline instead of towering
over the sentence they are in.

### Where the declared size actually lives

**Do not assume the `width`/`height` attributes are there.** gomuks' sanitizer rewrites every
`<img>` it renders server-side, and the size comes back as CSS. From a real profile bio:

```
img (picture): class="hicli-inline-img hicli-sized-inline-img"
               style="width: 320.00px; height: 99.00px;"
               src="_gomuks/media/server/mediaId?encrypted=false"
img (emoji):   class="hicli-inline-img hicli-custom-emoji"     ← no style at all
```

`mxc://` has become `_gomuks/media/…` and `data-mx-emoticon` has become a class. `appendImage`
therefore reads the attributes *and* falls back to `parseCssImageSizePx` on the `style`. Markup a
client stored itself (`chat.commet.profile_bio`, message `formatted_body`) is not sanitized and
still carries real attributes, so both paths are live.

A useful consequence: a picture has a size and a custom emoji doesn't, so "no declared size" is
also the signal that an image belongs on the text line. Nothing needs to look for the emoji marker.

### Sizing inline images in document views

That default is wrong wherever the markup is being shown as a document rather than as a chat line.
`HtmlMessageText`/`HtmlBodyText` therefore take an optional `inlineImageSizing:
InlineImageSizing(maxHeightSp, maxWidthSp)`. When set, `inlineImageSizeSp` sizes each image from
the `width`/`height` attributes the markup declared — preserving their aspect ratio — and shrinks
it to fit inside both bounds. It only ever **shrinks**: a declared `height="32"` stays 32.

Markup that declares no size falls back to the ordinary inline size (one line of text), *not* to
the cap. A browser would use the image's intrinsic size, which isn't known until the bitmap loads
while a `Placeholder` must be sized before that — and sizing those from the cap is what broke the
first version of this: gomuks' server-rendered bios drop `height` from some images, so every
emoticon in a bio came out as a full-width square.

The only caller today is `ExpandedBioDialog` (`utils/UserInfo.kt`), the floating window behind the
profile screen's fixed-height bio card. It passes its own `BoxWithConstraints` width, so images are
bounded by the actual window rather than by a guess at the screen size.

### Why a real image cannot be inline content

Inline content is a `Placeholder` inside a `Text`, and the line it lands on takes its height from
the text style's `lineHeight`. A 99dp-tall placeholder on a 17dp line therefore hangs outside the
line box, and the following text is laid out — and drawn — straight through the image. No
`PlaceholderVerticalAlign` avoids this; `TextCenter` and `Center` were both tried against a real
bio. Inline content is also measured before the container width is known, so it cannot be fitted
to the available space.

`splitTopLevelBlockImages` therefore splits a document-style body into `HtmlSegment.Markup` runs
and `HtmlSegment.BlockImage` entries, **in document order**, so a banner above the text renders
above the text. Only *top-level* images with a declared size qualify: an emoji has no size and
stays inline, and an image nested inside a paragraph or link keeps its place in the flow. The
caller sizes each block with `blockImageSize` (pure, Dp throughout, never upscales) and renders it
with `BlockHtmlImage`. `ExpandedBioDialog` is the only caller today.

An earlier attempt (`982e014d`, reverted in `a30654d2`) did the same split but keyed on
`data-mx-emoticon` and appended the images after the text. Both are wrong for sanitized markup: the
attribute is gone, so custom emoji were ejected from the text and re-rendered as banners at the
bottom of the bio.

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
