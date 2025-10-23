# HTML Rendering Implementation

## Overview
This document describes the HTML rendering feature added to Andromuks for displaying rich formatted messages according to the Matrix specification.

## What Was Implemented

### 1. HTML Parser and Renderer (`utils/html.kt`)
Created a comprehensive HTML parsing and rendering system that:

- **Supports Matrix-approved HTML tags**: del, h1-h6, blockquote, p, a, ul, ol, sup, sub, li, b, i, u, strong, em, s, code, hr, br, div, table, thead, tbody, tr, th, td, caption, pre, span, img, details, summary
- **Security-first approach**: Only whitelisted tags are rendered; all others are ignored
- **Inline image support**: Renders images from MXC URLs directly inline with text
- **Proper MXC URL handling**: Converts MXC URLs to HTTP URLs and refuses to load images from direct HTTP(S) URLs for security
- **Fallback support**: Gracefully falls back to plain text if HTML parsing fails

#### Key Components:

##### HtmlParser
- Parses sanitized HTML into a tree structure (`HtmlNode`)
- Handles nested tags correctly
- Validates tags against the whitelist
- Special handling for self-closing tags (br, hr, img)
- Validates image sources (only MXC URLs or gomuks media paths)

##### HtmlRenderer
- Converts parsed HTML nodes to Compose `AnnotatedString`
- Applies proper styling for all supported tags
- Creates inline content placeholders for images
- Supports text formatting: **bold**, *italic*, underline, strikethrough, code
- Supports headings (h1-h6) with appropriate sizing
- Supports lists (ul, ol) with bullets and numbering
- Supports blockquotes and preformatted text

##### HtmlMessageText Composable
- Main entry point for rendering HTML messages
- Automatically extracts `sanitized_html` from event's `local_content`
- Falls back to plain text body if HTML is not available
- Renders inline images using Coil's `AsyncImage` with authentication

### 2. Updated TimelineEvent Model
Added `localContent` field to `TimelineEvent` class:
- Parses `local_content` from event JSON (top-level field)
- Contains `sanitized_html` and `html_version` fields

### 3. Integration with RoomTimelineScreen
Created `AdaptiveMessageText` composable that:
- Automatically detects if an event has HTML content
- Chooses between HTML rendering and plain text rendering
- Passes correct text colors based on bubble theme
- Maintains all existing functionality (mentions, links, etc.)

Replaced all `SmartMessageText` calls with `AdaptiveMessageText` in:
- Regular text messages
- Reply messages
- Encrypted text messages
- Encrypted reply messages

## Matrix Specification Compliance

According to Matrix spec:
> "Some message types support HTML in the event content that clients should prefer to display if available. Currently m.text, m.emote, m.notice, m.image, m.file, m.audio, m.video and m.key.verification.request support an additional format parameter of org.matrix.custom.html."

Our implementation:
- ✅ Checks for `format: "org.matrix.custom.html"`
- ✅ Supports all mentioned message types (m.text, m.emote, m.notice, etc.)
- ✅ Uses sanitized HTML from `local_content.sanitized_html`
- ✅ Falls back to plain text `body` when HTML is not available
- ✅ Implements the recommended whitelist of HTML tags
- ✅ Prevents XSS and HTML injection attacks

## Security Features

1. **Tag Whitelist**: Only Matrix-approved tags are rendered
2. **MXC URL Validation**: Images must use MXC URLs, not direct HTTP(S)
3. **Sanitized HTML**: Uses pre-sanitized HTML from gomuks backend
4. **Safe Attribute Parsing**: Extracts only necessary attributes (src, alt, title, href, height)
5. **Authentication**: All media requests include authentication cookies

## Example Usage

### Input Event (JSON):
```json
{
  "type": "m.room.message",
  "content": {
    "body": "**BOLD** _Italic_",
    "format": "org.matrix.custom.html",
    "formatted_body": "<strong>BOLD</strong> <em>Italic</em>",
    "msgtype": "m.text"
  },
  "local_content": {
    "sanitized_html": "<strong>BOLD</strong> <em>Italic</em>",
    "html_version": 11
  }
}
```

### Rendered Output:
- **BOLD** *Italic*

### With Inline Images:
```json
{
  "content": {
    "body": "Hello :emoji: World",
    "format": "org.matrix.custom.html",
    "msgtype": "m.text"
  },
  "local_content": {
    "sanitized_html": "Hello <img src=\"mxc://server/mediaId\" alt=\":emoji:\" height=\"32\"> World"
  }
}
```

Renders as: Hello [emoji image] World

## Technical Details

### HTML Parsing Flow
1. Extract `sanitized_html` from `event.localContent`
2. Parse HTML string into `HtmlNode` tree
3. Validate all tags against whitelist
4. Handle special tags (img, br, hr) appropriately
5. Convert to `AnnotatedString` with styling
6. Create `InlineTextContent` for images
7. Render with Compose `Text` component

### Image Rendering Flow
1. Detect `<img>` tags in HTML
2. Extract MXC URL from `src` attribute
3. Convert MXC URL to HTTP URL using `MediaUtils.mxcToHttpUrl()`
4. Create placeholder in `AnnotatedString`
5. Render with `AsyncImage` (Coil) with authentication
6. Fallback to alt text if image fails to load

## Files Modified

1. **app/src/main/java/net/vrkknn/andromuks/utils/html.kt** (NEW)
   - 600+ lines of HTML parsing and rendering code
   
2. **app/src/main/java/net/vrkknn/andromuks/TimelineEvent.kt**
   - Added `localContent: JSONObject?` field
   - Updated `fromJson()` to parse `local_content`
   
3. **app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt**
   - Added imports for HTML rendering functions
   - Created `AdaptiveMessageText` composable
   - Replaced 4 instances of `SmartMessageText` with `AdaptiveMessageText`

## Testing Recommendations

1. Test with various HTML formatting:
   - Bold, italic, underline, strikethrough
   - Headings (h1-h6)
   - Links
   - Lists (ordered and unordered)
   - Blockquotes
   - Code blocks
   - Inline code

2. Test with inline images:
   - Custom emoji (mxc:// URLs)
   - Stickers
   - Various image sizes

3. Test edge cases:
   - Malformed HTML
   - Disallowed tags
   - HTTP(S) image URLs (should be blocked)
   - Missing sanitized_html (should fallback)
   - Very long messages with mixed HTML

4. Test in different contexts:
   - Regular messages
   - Reply messages
   - Encrypted messages
   - Messages from bridges (e.g., Beeper)

## Future Enhancements

Potential improvements for future versions:

1. **Table rendering**: Proper table layout (currently simplified)
2. **Nested lists**: Better handling of nested ul/ol
3. **Superscript/Subscript**: True baseline shift when Compose supports it
4. **Link clicking**: Make links clickable with proper handlers
5. **Details/Summary**: Expandable sections
6. **Image sizing**: Respect width attribute in addition to height
7. **Performance**: Cache parsed HTML nodes for frequently viewed messages

## Notes

- The implementation uses the `sanitized_html` field from `local_content`, which is pre-sanitized by the gomuks backend (html_version: 11)
- All image loading respects authentication and uses the existing media infrastructure
- The HTML renderer is defensive and will gracefully degrade to plain text on any errors

