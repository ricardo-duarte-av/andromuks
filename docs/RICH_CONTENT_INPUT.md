# Rich content from the keyboard (GIF / sticker insertion)

Gboard's GIF and sticker buttons are greyed out unless the focused editor **advertises** that it
accepts rich content. That advertisement is two things together:

1. `EditorInfo.contentMimeTypes`, set via `EditorInfoCompat.setContentMimeTypes`, and
2. an `InputConnection` whose `commitContent` actually handles the committed URI.

Before this, the app declared neither, which is why [issue #30](https://github.com/ricardo-duarte-av/andromuks/issues/30)
saw both buttons disabled. Nothing was broken — the capability had simply never been declared.

## Why not `Modifier.contentReceiver`

The Compose-native answer is `androidx.compose.foundation.content.contentReceiver`, and it does
**not** work here. In compose-foundation 1.12, only
`foundation.text.input.internal.TextFieldDecoratorModifierNode` and `AndroidTextInputSession`
reference `ReceiveContentConfiguration` — i.e. only the newer `BasicTextField(state: TextFieldState)`.

The composer (`utils/CustomTextField.kt` → `CustomBubbleTextField`) uses the **legacy**
`BasicTextField(value, onValueChange)`, whose `RecordingInputConnection.commitContent` is not wired
to receive-content and whose `EditorInfo` never gets `contentMimeTypes`. Migrating is not a bug fix:
`onValueChange` carries custom-emoji deletion, `:shortcode:` replacement, `/command`, `@mention` and
`#room` detection, and the mention-pill `VisualTransformation` would have to become an
`OutputTransformation`.

## What we do instead

`androidx.compose.ui.platform.InterceptPlatformTextInput` wraps the platform IME session for every
descendant, whichever text field implementation is underneath. `utils/RichContentInput.kt` uses it to:

1. call the original `request.createInputConnection(outAttributes)`,
2. stamp `RICH_CONTENT_MIME_TYPES` (`image/gif`, `image/png`, `image/webp`, `image/jpeg`) onto
   `outAttributes`, and
3. return `InputConnectionCompat.createWrapper(ic, outAttributes) { info, flags, _ -> … }`.

`CustomBubbleTextField` gained an optional `onReceiveRichContent` parameter and emits its
`BasicTextField` through the wrapper only when that parameter is non-null — so rich content stays
opt-in per call site rather than being advertised by every text field in the app.

### The deprecated `createWrapper` overload

The listener-taking `createWrapper(ic, editorInfo, listener)` is deprecated in favour of
`createWrapper(view, ic, editorInfo)`, which routes `commitContent` through an
`OnReceiveContentListener` installed **on the View**. The only View available inside the interceptor
is Compose's own `AndroidComposeView`; taking it over for the session would reach far beyond this one
text field. The listener overload is kept deliberately (with `@Suppress("DEPRECATION")`) because its
scope is exactly the connection we wrapped.

### URI permission lifetime

The IME hands over a *temporary* read grant (`INPUT_CONTENT_GRANT_READ_URI_PERMISSION` →
`info.requestPermission()`), which is not safe to hold across a multi-second upload. So the bytes are
copied immediately into `cacheDir/rich_content/` (declared in `res/xml/file_paths.xml`), the
permission is released in a `finally`, and the upload runs against a FileProvider URI we own. Stale
files are pruned on the next commit — only ones older than `RICH_CONTENT_RETENTION_MS`, since a send
from moments ago may still be streaming.

## Send behaviour

Committed content is uploaded and **sent immediately** — no preview, no caption step. It still
inherits the composer's reply target (and, in `ThreadViewerScreen`, the thread root), matching what
the attachment picker does.

The IME never says which button was tapped, so the msgtype is chosen from the MIME type:

| Committed MIME | Sent as | Path |
|---|---|---|
| `image/gif` | `m.image` with `is_animated` | `sendImageMessage` |
| anything else (PNG / WebP / JPEG) | `m.sticker` | `sendStickerMessage` |

This matches Gboard: its GIF button commits `image/gif`, its sticker / Emoji Kitchen button commits
PNG or WebP. It is a heuristic, and the only signal available.

## Animated media, generally

Two related fixes landed with this, and they apply to **every** send path, not just the keyboard:

- `MessageSendCoordinator.sendMediaMessage` takes `isAnimated` and writes `info.is_animated`
  (MSC4230). `sendImageMessage` sets it automatically for `image/gif`. We already parsed this field
  on receive (`utils/MediaContentParser.kt`, consumed in `utils/MediaFunctions.kt` to skip the static
  thumbnail) — we just never sent it, so our own GIFs showed a still frame elsewhere.
- `MediaUploadUtils.uploadMedia` now refuses to re-encode `image/gif` and `image/webp` whatever
  `compressOriginal` says. Re-encoding runs the image through `BitmapFactory` + JPEG, which keeps only
  the first frame; these formats carry no EXIF orientation to correct either.

The JPEG thumbnail and BlurHash are still generated from frame 1, which keeps placeholder behaviour
consistent.
