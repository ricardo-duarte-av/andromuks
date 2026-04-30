# Emoji System

## Source of Truth

All Unicode emoji data is sourced from **[iamcal/emoji-data](https://github.com/iamcal/emoji-data)** (MIT licensed). The dataset provides the full emoji list, official category groupings, display names, and shortcode aliases used by Slack, Discord, and GitHub.

**`EmojiData.kt` and `EmojiShortcodes.kt` are generated files.** Do not edit them by hand.

## Generator

```
scripts/generate_emoji_kt.py   — generator script
scripts/emoji.json             — cached iamcal dataset (gitignored)
```

Run to regenerate (e.g. when a new Unicode version is released):

```bash
python3 scripts/generate_emoji_kt.py
```

The script downloads `emoji.json` on first run and caches it locally. It produces:

| Output file | Contents |
|---|---|
| `utils/EmojiData.kt` | Category lists, name map, shortcode map, `searchEmojis()` |
| `utils/EmojiShortcodes.kt` | `shortcodeToEmoji`, `emojiToShortcode`, autocomplete logic, `EmojiSuggestionList` composable |

## JVM 64 KB Limit

The JVM limits any single method to 64 KB of bytecode. A `mapOf(1900+ entries)` in a top-level `val` inlines all assignments into `<clinit>`, blowing past this limit.

**Fix:** each large map is split into private chunk functions of 150 entries each via `buildMap { chunkN(this) }`. Each chunk function is ~4 KB. `<clinit>` only calls them.

The `chunked_map_kt()` helper in the generator handles this automatically. Do not collapse chunks back into a single `mapOf()`.

## Search

`EmojiData.searchEmojis(query)` matches against:
1. **Display name** — e.g. `"melting"` finds 🫠 (`"melting face"`)
2. **All shortcodes** — e.g. `"smile"` finds 😃 (shortcode `"smiley"`) and 😄 (shortcode `"smile"`)

The shortcode map stored in `EmojiData` is emoji → space-joined string of all its shortcodes, so a single `String.contains()` check covers all aliases.

## Picker UI (`EmojiSelection.kt`)

Tab index scheme (fixed; do not shift):

| Index | Tab | Source |
|---|---|---|
| 0 | 🕒 Recent | `recentEmojis` arg |
| 1 | 🌐 All | `EmojiData.getAllEmojis()` |
| 2–10 | Category tabs | `emojiCategories[selectedCategory - 1]` |
| 11+ | Custom emoji packs | `customEmojiPacks[selectedCategory - 1 - emojiCategories.size]` |

When the user types in the search box, a `LaunchedEffect` automatically switches to the All tab (index 1). `EmojiData.searchEmojis()` runs across all emojis regardless of which category was previously selected.

The `allEmojis` list is computed once via `remember { EmojiData.getAllEmojis() }` to avoid rebuilding on every recomposition.
