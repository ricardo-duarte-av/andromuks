# HTML Rendering (`utils/html.kt`, `utils/HtmlTableRenderer.kt`)

## Overview

`HtmlMessageText` is the main composable for rendering Matrix `formatted_body` HTML. It parses HTML into a tree of `HtmlNode` via `HtmlParser`, builds an `AnnotatedString` from non-table nodes, and renders it in a `Text()` with custom gesture handling for links, spoilers, and code blocks.

## The Render Walk (`renderHtmlNodes`)

`renderHtmlNodes(nodes, baseStyle, …)` turns the parsed node list into the `AnnotatedString`.
There is exactly **one** walk, and `HtmlMessageText` calls it — if you are fixing how markup
renders, this is the only place to fix it.

It has not always been that way. The walk used to be inlined in `HtmlMessageText`'s
`derivedStateOf`, with a simplified near-copy (`renderHtmlToAnnotatedString`) at the bottom of the
file "for simple display (profile bios, etc.)". Nothing called the copy — bios go through
`HtmlBodyText`, which delegates to `HtmlMessageText` like everything else — and it had drifted:
it skipped the blank line between consecutive `<p>` and trimmed one trailing newline where the
message path trimmed all of them. Two walks meant every fix to the block bookkeeping had to be
made twice, or silently wasn't. **Do not add a second one.** A caller that needs less should pass
less, which is what the nullable arguments are for:

- `spoilerContext == null` — no state to store revealed/hidden in, so spoiler markup renders
  plainly rather than staying masked with no way to toggle it.
- `inlineCodeBlocks == null` — no viewer to open a full listing in, so `<pre><code>` renders as
  preformatted text instead of a tappable 8-line preview.

Callers own the four inline-content maps and clear them before each render; the walk fills them
with the placeholders the caller then supplies as `inlineContent`. Exceptions propagate —
`HtmlMessageText` catches them so a bad message cannot take down a timeline row, but a test sees
the failure.

### Line bookkeeping

Nearly every rendering bug this file has had was a stray or missing `\n`, so the conventions are
worth stating:

- A block appender that opens a line guards with `length > 0 && !endsWithNewline()` — **never** a
  bare `!endsWithNewline()`, which is false at length 0 and so opens the message with a blank
  line. Missing that guard is what made a leading `<blockquote>`, `<ul>` and `<ol>` each emit a
  blank first line.
- `appendBlockQuote` prefixes every quoted line with `│ ` (one per nesting level) and tracks
  `lineOpen` — whether the current output line already carries that prefix. A `<br>` opens a
  prefixed line and the content after it must fill *that* line; without the flag the text branch
  opened a second one and `one<br/>two` rendered as three lines with an empty `│` between them.
  Leading and trailing `<br>` children are dropped by `trimEdgeLineBreaks()`, since gomuks emits
  `<blockquote>text<br/></blockquote>` for a one-line markdown quote and that trailing break would
  otherwise leave a dangling `│` of its own.
- An empty `<p>`/`<div>` is **dropped**, not rendered — `appendHtmlTag` skips it when
  `rendersVisibleContent` says its subtree puts nothing on screen, and the consecutive-paragraph
  lookahead skips over it too so `<p>a</p><p></p><p>b</p>` keeps exactly one blank line rather
  than losing its separator to the empty block. Emptiness is decided by *output*, not by having
  children: `<br>`, `<img>`, `<hr>` and `<hicli-math>` render on their own, and a `display: none`
  subtree renders nothing however much text it holds.

  This matters because senders really do emit empty paragraphs. `<pre>` is not allowed inside
  `<p>`, so `HtmlParser` closes the paragraph implicitly the way browsers do, leaving an empty
  `<p></p>` on each side of the block — and matrix-hookshot's default webhook formatter emits
  exactly `<p>…</p><p><pre><code>…</code></pre></p>`. Each empty paragraph used to open a line of
  its own on top of the paragraph separator, putting three newlines between the sentence and the
  code where the well-formed markup gives one.
- The trailing-newline trim lives inside `renderHtmlNodes`, so every caller gets it. Block
  rendering closes every line it opens, so the last one always leaves a newline behind.
- `endsWithWhitespace()` and `endsWithNewline()` both read `toAnnotatedString().text`.
  `AnnotatedString.Builder` does **not** override `toString()`, so calling it inspects
  `…Builder@36916eb0` — `endsWithWhitespace` did exactly that for a long time, answered false for
  every input, and put two spaces after every mention pill.

### Golden tests

`HtmlRenderGoldenTest` drives `renderHtmlNodes` directly. It is a plain function rather than a
composable body, so the real message path runs in a JVM unit test with no Compose runtime — that
is a reason to keep it one, not an incidental detail.

Assertions are on the **exact string, newlines included**. Four shipped defects were found by
writing that file, and every one of them was a whitespace difference that a test normalising
whitespace would have passed straight over. `HtmlParserTest` covers the parse; it cannot catch any
of this.

It also covers `buildPlainTextAnnotatedStringWithCode`, the no-`formatted_body` path, which is
`internal` rather than private for exactly that reason. Its fences buffer their contents instead of
appending line by line, so blank lines padding a fence (hookshot's plain body is
``"…\n\n```json\n\n<json>\n\n```"``) are trimmed at the edges while blank lines *inside* the code
survive. A message that opens with a fence no longer starts on a blank line, and an unterminated
fence still flushes what it collected.

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
