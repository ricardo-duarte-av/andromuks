# Sticky Date Indicator (`utils/StickyDateIndicator.kt`)

A pill-shaped overlay that appears at the top of each timeline screen (below the header) showing the date of the oldest visible event. Used in `RoomTimelineScreen`, `BubbleTimelineScreen`, `ThreadViewerScreen`, and `EventContextScreen`.

## Behaviour

- Slides in (down) + fades in when the user scrolls toward older content and the oldest visible event is not from today.
- Resets the 3-second auto-hide timer on every item-boundary crossing while scrolling toward older content.
- Hides immediately (slide up + fade out) when scrolling toward newer content.
- Auto-hides after 3 seconds of no scroll activity.

## Parameters

| Parameter | Description |
|---|---|
| `oldestVisibleDate: String?` | `"dd / MM / yyyy"` formatted date of the oldest on-screen event. |
| `scrollPositionKey: Int` | Changes on every item-boundary crossing (driven by `listState.firstVisibleItemIndex`). |
| `reverseScrollLayout: Boolean` | `true` for `reverseLayout=true` LazyColumns where increasing index = older content; `false` for top-down layouts. |

## Clipping Invariant

Each host screen wraps its LazyColumn in a `Box` with `.clipToBounds()`. Without this, the slide-in animation renders the pill *above* the header instead of emerging from behind it.

## Layout Differences by Screen

| Screen | `reverseLayout` | "oldest" item index | `reverseScrollLayout` |
|--------|----------------|--------------------|-----------------------|
| `RoomTimelineScreen` | `true` | `maxOfOrNull { it.index }` from `visibleItemsInfo` | `true` |
| `BubbleTimelineScreen` | `true` | `maxOfOrNull { it.index }` from `visibleItemsInfo` | `true` |
| `ThreadViewerScreen` | `false` | `firstVisibleItemIndex` | `false` |
| `EventContextScreen` | `false` | `firstVisibleItemIndex` | `false` |

## Date Formatting

`formatDate()` (defined in `RoomTimelineScreen.kt`, `internal` visibility) is used for timestamp → `"dd / MM / yyyy"` conversion in Room, Thread, and EventContext screens. `BubbleTimelineScreen` uses `java.text.SimpleDateFormat` inline (different file, same package).
