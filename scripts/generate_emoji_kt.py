#!/usr/bin/env python3
"""
Generate EmojiData.kt and EmojiShortcodes.kt from iamcal/emoji-data emoji.json.

Usage:
    python3 scripts/generate_emoji_kt.py

Downloads emoji.json on first run and caches it as scripts/emoji.json.
Re-run whenever a new Unicode version is released.
"""

import json
import urllib.request
import os
import sys

EMOJI_JSON_URL = "https://raw.githubusercontent.com/iamcal/emoji-data/master/emoji.json"
CACHE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "emoji.json")

SRC = "app/src/main/java/net/vrkknn/andromuks/utils"
EMOJI_DATA_OUT = os.path.join(SRC, "EmojiData.kt")
SHORTCODES_OUT = os.path.join(SRC, "EmojiShortcodes.kt")

CATEGORY_META = [
    ("Smileys & Emotion", "😀"),
    ("People & Body",     "👥"),
    ("Animals & Nature",  "🐶"),
    ("Food & Drink",      "🍎"),
    ("Travel & Places",   "✈️"),
    ("Activities",        "⚽"),
    ("Objects",           "📱"),
    ("Symbols",           "❤️"),
    ("Flags",             "🏳️"),
]
SKIP_CATEGORIES = {"Component"}


CHUNK = 150  # entries per chunk function — keeps each function well under 64 KB


def chunked_map_kt(var_name: str, items: list, indent: str = "") -> list[str]:
    """
    Emit a Map<String,String> split into private chunk functions to stay under the
    JVM 64 KB per-method bytecode limit.  Each chunk function does plain assignments
    into a MutableMap; the top-level val assembles them via buildMap {}.

    items: list of (key_str, value_str) — both already unescaped Python strings.
    """
    out = []
    chunks = [items[i:i+CHUNK] for i in range(0, len(items), CHUNK)]
    cap = len(items) + 16

    out.append(f'{indent}private val {var_name}: Map<String, String> = buildMap({cap}) {{')
    for i in range(len(chunks)):
        out.append(f'{indent}    {var_name}Chunk{i}(this)')
    out.append(f'{indent}}}')
    out.append('')
    for i, chunk in enumerate(chunks):
        out.append(f'{indent}private fun {var_name}Chunk{i}(m: MutableMap<String, String>) {{')
        for k, v in chunk:
            out.append(f'{indent}    m["{esc(k)}"] = "{esc(v)}"')
        out.append(f'{indent}}}')
        out.append('')
    return out


def unified_to_char(unified: str) -> str:
    return "".join(chr(int(cp, 16)) for cp in unified.split("-"))


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def load_emoji_json() -> list:
    if not os.path.exists(CACHE_PATH):
        print(f"Downloading emoji.json …", file=sys.stderr)
        urllib.request.urlretrieve(EMOJI_JSON_URL, CACHE_PATH)
        print(f"Saved to {CACHE_PATH}", file=sys.stderr)
    else:
        print(f"Using cached {CACHE_PATH}", file=sys.stderr)
    with open(CACHE_PATH, encoding="utf-8") as f:
        return json.load(f)


def build_data(entries: list):
    by_category: dict[str, list[str]] = {name: [] for name, _ in CATEGORY_META}
    name_map: dict[str, str] = {}        # emoji char -> display name
    shortcode_map: dict[str, str] = {}   # shortcode -> emoji char

    for entry in sorted(entries, key=lambda e: e.get("sort_order", 9999)):
        cat = entry.get("category", "")
        if cat in SKIP_CATEGORIES:
            continue
        unified = entry.get("unified", "")
        if not unified:
            continue

        char = unified_to_char(unified)
        display_name = entry.get("name", "").lower()
        short_names: list[str] = entry.get("short_names", [])

        if cat in by_category:
            by_category[cat].append(char)

        name_map[char] = display_name
        for sn in short_names:
            sn_clean = sn.strip().lower()
            if sn_clean and sn_clean not in shortcode_map:
                shortcode_map[sn_clean] = char

    return by_category, name_map, shortcode_map


def render_emoji_data_kt(categories: dict, name_map: dict, shortcode_map: dict) -> str:
    # Build emoji -> all shortcodes that reference it (joined by space for substring search)
    emoji_to_shortcodes: dict[str, list[str]] = {}
    for sc, em in shortcode_map.items():
        emoji_to_shortcodes.setdefault(em, []).append(sc)

    out = []
    out.append('package net.vrkknn.andromuks.utils')
    out.append('')
    out.append('object EmojiData {')
    out.append('')
    out.append('    fun getEmojiCategories(): List<EmojiCategory> = emojiCategories')
    out.append('')
    out.append('    fun getAllEmojis(): List<String> = emojiCategories.flatMap { it.emojis }')
    out.append('')
    out.append('    fun searchEmojis(query: String): List<String> {')
    out.append('        val q = query.trim().lowercase()')
    out.append('        if (q.isBlank()) return emptyList()')
    out.append('        return getAllEmojis().filter { emoji ->')
    out.append('            emojiNameMap[emoji]?.contains(q) == true ||')
    out.append('            emojiShortcodesMap[emoji]?.contains(q) == true')
    out.append('        }')
    out.append('    }')
    out.append('')
    out.append('    fun getEmojiName(emoji: String): String? = emojiNameMap[emoji]')
    out.append('}')
    out.append('')

    # Category list
    out.append('private val emojiCategories = listOf(')
    out.append('    EmojiCategory("\\uD83D\\uDD52", "Recent", listOf()),')
    for cat_name, icon in CATEGORY_META:
        emojis = categories.get(cat_name, [])
        out.append(f'    EmojiCategory("{esc(icon)}", "{cat_name}",')
        out.append(f'        listOf(')
        for i in range(0, len(emojis), 16):
            chunk = emojis[i:i+16]
            quoted = ", ".join(f'"{esc(e)}"' for e in chunk)
            comma = "," if i + 16 < len(emojis) else ""
            out.append(f'            {quoted}{comma}')
        out.append(f'        )')
        out.append(f'    ),')
    out.append(')')
    out.append('')

    # Emoji → display name (chunked to avoid JVM 64 KB method limit)
    out += chunked_map_kt('emojiNameMap', list(name_map.items()))

    # Emoji → space-joined shortcodes (chunked)
    sc_items = [(emoji, " ".join(shortcodes)) for emoji, shortcodes in emoji_to_shortcodes.items()]
    out += chunked_map_kt('emojiShortcodesMap', sc_items)

    return "\n".join(out)


def render_shortcodes_kt(shortcode_map: dict) -> str:
    # Build reverse: emoji -> alphabetically-first shortcode
    emoji_to_sc: dict[str, str] = {}
    for sc, em in shortcode_map.items():
        if em not in emoji_to_sc or sc < emoji_to_sc[em]:
            emoji_to_sc[em] = sc

    out = []
    out.append('package net.vrkknn.andromuks.utils')
    out.append('')
    out.append('import androidx.compose.foundation.border')
    out.append('import androidx.compose.foundation.clickable')
    out.append('import androidx.compose.foundation.layout.Arrangement')
    out.append('import androidx.compose.foundation.layout.Box')
    out.append('import androidx.compose.foundation.layout.PaddingValues')
    out.append('import androidx.compose.foundation.layout.Row')
    out.append('import androidx.compose.foundation.layout.Spacer')
    out.append('import androidx.compose.foundation.layout.height')
    out.append('import androidx.compose.foundation.layout.padding')
    out.append('import androidx.compose.foundation.layout.width')
    out.append('import androidx.compose.foundation.layout.widthIn')
    out.append('import androidx.compose.foundation.lazy.LazyColumn')
    out.append('import androidx.compose.foundation.lazy.items')
    out.append('import androidx.compose.foundation.shape.RoundedCornerShape')
    out.append('import androidx.compose.material3.MaterialTheme')
    out.append('import androidx.compose.material3.Surface')
    out.append('import androidx.compose.material3.Text')
    out.append('import androidx.compose.runtime.Composable')
    out.append('import androidx.compose.runtime.remember')
    out.append('import androidx.compose.ui.Alignment')
    out.append('import androidx.compose.ui.Modifier')
    out.append('import androidx.compose.ui.text.font.FontWeight')
    out.append('import androidx.compose.ui.unit.dp')
    out.append('import net.vrkknn.andromuks.AppViewModel')
    out.append('')
    out.append('data class EmojiSuggestion(')
    out.append('    val shortcode: String,')
    out.append('    val emoji: String? = null,')
    out.append('    val customEmoji: AppViewModel.CustomEmoji? = null')
    out.append(')')
    out.append('')
    out.append('object EmojiShortcodes {')
    out += chunked_map_kt('shortcodeToEmoji', sorted(shortcode_map.items()), indent='    ')
    out += chunked_map_kt('emojiToShortcode', sorted(emoji_to_sc.items(), key=lambda x: x[1]), indent='    ')

    out.append('    fun getSuggestions(')
    out.append('        query: String,')
    out.append('        customEmojiPacks: List<AppViewModel.EmojiPack>,')
    out.append('        maxResults: Int = 25')
    out.append('    ): List<EmojiSuggestion> {')
    out.append('        val trimmed = query.trim().lowercase()')
    out.append('')
    out.append('        val standardMatches = shortcodeToEmoji')
    out.append('            .asSequence()')
    out.append('            .filter { (name, _) ->')
    out.append('                trimmed.isEmpty() || name.startsWith(trimmed)')
    out.append('            }')
    out.append('            .sortedBy { it.key }')
    out.append('            .map { (name, emoji) -> EmojiSuggestion(shortcode = name, emoji = emoji) }')
    out.append('')
    out.append('        val customMatches = customEmojiPacks')
    out.append('            .asSequence()')
    out.append('            .flatMap { it.emojis.asSequence() }')
    out.append('            .filter { emoji ->')
    out.append('                val name = emoji.name.lowercase()')
    out.append('                trimmed.isEmpty() || name.startsWith(trimmed)')
    out.append('            }')
    out.append('            .sortedBy { it.name }')
    out.append('            .map { emoji -> EmojiSuggestion(shortcode = emoji.name, customEmoji = emoji) }')
    out.append('')
    out.append('        return (standardMatches + customMatches).take(maxResults).toList()')
    out.append('    }')
    out.append('')
    out.append('    fun findByShortcode(')
    out.append('        shortcodeWithoutColons: String,')
    out.append('        customEmojiPacks: List<AppViewModel.EmojiPack>')
    out.append('    ): EmojiSuggestion? {')
    out.append('        val key = shortcodeWithoutColons.trim().lowercase()')
    out.append('        shortcodeToEmoji[key]?.let { return EmojiSuggestion(shortcode = key, emoji = it) }')
    out.append('        customEmojiPacks')
    out.append('            .asSequence()')
    out.append('            .flatMap { it.emojis.asSequence() }')
    out.append('            .firstOrNull { it.name.equals(key, ignoreCase = true) }')
    out.append('            ?.let { return EmojiSuggestion(shortcode = it.name, customEmoji = it) }')
    out.append('        return null')
    out.append('    }')
    out.append('}')
    out.append('')
    out.append(EMOJI_SUGGESTION_LIST_COMPOSABLE)

    return "\n".join(out)


EMOJI_SUGGESTION_LIST_COMPOSABLE = """\
@Composable
fun EmojiSuggestionList(
    query: String,
    customEmojiPacks: List<AppViewModel.EmojiPack>,
    homeserverUrl: String,
    authToken: String,
    onSuggestionSelected: (EmojiSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = remember(query, customEmojiPacks) {
        EmojiShortcodes.getSuggestions(query, customEmojiPacks)
    }

    if (suggestions.isEmpty()) return

    Surface(
        modifier = modifier
            .widthIn(max = 260.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        LazyColumn(
            modifier = Modifier.height(200.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(suggestions) { suggestion ->
                Row(
                    modifier = Modifier
                        .clickable { onSuggestionSelected(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            suggestion.emoji != null -> Text(
                                text = suggestion.emoji,
                                style = MaterialTheme.typography.titleLarge
                            )
                            suggestion.customEmoji != null -> ImageEmoji(
                                mxcUrl = suggestion.customEmoji.mxcUrl,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = ":${suggestion.shortcode}:",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
"""


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(os.path.join(script_dir, ".."))

    entries = load_emoji_json()
    print(f"Loaded {len(entries)} entries", file=sys.stderr)

    categories, name_map, shortcode_map = build_data(entries)

    for cat_name, _ in CATEGORY_META:
        print(f"  {cat_name}: {len(categories.get(cat_name, []))} emojis", file=sys.stderr)
    print(f"  Shortcodes: {len(shortcode_map)}", file=sys.stderr)
    print(f"  Name map:   {len(name_map)}", file=sys.stderr)

    with open(EMOJI_DATA_OUT, "w", encoding="utf-8") as f:
        f.write(render_emoji_data_kt(categories, name_map, shortcode_map))
    print(f"Wrote {EMOJI_DATA_OUT}", file=sys.stderr)

    with open(SHORTCODES_OUT, "w", encoding="utf-8") as f:
        f.write(render_shortcodes_kt(shortcode_map))
    print(f"Wrote {SHORTCODES_OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
