package net.vrkknn.andromuks.utils

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class TableData(
    val headers: List<String>,
    val rows: List<List<String>>,
    val columnCount: Int
)

fun parseTableNode(tableNode: HtmlNode.Tag): TableData {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()

    fun extractText(node: HtmlNode): String = when (node) {
        is HtmlNode.Text -> node.content
        is HtmlNode.LineBreak -> " "
        is HtmlNode.Tag -> node.children.joinToString("") { extractText(it) }
    }

    fun processTr(trNode: HtmlNode.Tag, isHeader: Boolean) {
        val cells = trNode.children
            .filterIsInstance<HtmlNode.Tag>()
            .filter { it.name == "td" || it.name == "th" }
            .map { extractText(it).trim() }
        if (isHeader) {
            headers.addAll(cells)
        } else {
            rows.add(cells)
        }
    }

    for (child in tableNode.children) {
        if (child !is HtmlNode.Tag) continue
        when (child.name) {
            "thead" -> child.children.filterIsInstance<HtmlNode.Tag>()
                .filter { it.name == "tr" }.forEach { processTr(it, true) }
            "tbody", "tfoot" -> child.children.filterIsInstance<HtmlNode.Tag>()
                .filter { it.name == "tr" }.forEach { processTr(it, false) }
            "tr" -> {
                val isHeaderRow = child.children.filterIsInstance<HtmlNode.Tag>().any { it.name == "th" }
                processTr(child, isHeaderRow && headers.isEmpty())
            }
            "caption" -> { /* skip caption in table data */ }
        }
    }

    val columnCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    return TableData(headers, rows, columnCount)
}

@Composable
fun HtmlTablePreviewCard(
    tableData: TableData,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val headerPreview = tableData.headers.take(4).joinToString(", ").let {
        if (tableData.columnCount > 4) "$it…" else it
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TableChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "${tableData.rows.size} rows × ${tableData.columnCount} columns",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (headerPreview.isNotEmpty()) {
                    Text(
                        text = headerPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Tap to view",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun HtmlTableDialog(
    tableData: TableData,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Table — ${tableData.rows.size} rows × ${tableData.columnCount} columns",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                HtmlTableContent(
                    tableData = tableData,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun HtmlTableContent(
    tableData: TableData,
    modifier: Modifier = Modifier
) {
    val minColWidth = 80.dp
    val maxColWidth = 220.dp
    val colCount = tableData.columnCount

    val colWidths: List<Dp> = remember(tableData) {
        (0 until colCount).map { colIdx ->
            val maxLen = maxOf(
                tableData.headers.getOrNull(colIdx)?.length ?: 0,
                tableData.rows.maxOfOrNull { it.getOrNull(colIdx)?.length ?: 0 } ?: 0
            )
            // ~7sp per char heuristic, clamped between min and max
            ((maxLen * 7f).coerceIn(minColWidth.value, maxColWidth.value)).dp
        }
    }

    val horizontalScrollState = rememberScrollState()
    val lazyListState = rememberLazyListState()

    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    val oddRowBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val colDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

    LazyColumn(
        state = lazyListState,
        modifier = modifier.horizontalScroll(horizontalScrollState)
    ) {
        if (tableData.headers.isNotEmpty()) {
            item(key = "header") {
                HtmlTableRow(
                    cells = tableData.headers,
                    colWidths = colWidths,
                    colCount = colCount,
                    backgroundColor = headerBg,
                    colDividerColor = colDividerColor,
                    isHeader = true
                )
                HorizontalDivider(color = dividerColor, thickness = 1.5.dp)
            }
        }
        itemsIndexed(tableData.rows, key = { idx, _ -> idx }) { rowIdx, row ->
            HtmlTableRow(
                cells = row,
                colWidths = colWidths,
                colCount = colCount,
                backgroundColor = if (rowIdx % 2 == 1) oddRowBg else Color.Transparent,
                colDividerColor = colDividerColor,
                isHeader = false
            )
            HorizontalDivider(color = dividerColor)
        }
    }
}

@Composable
private fun HtmlTableRow(
    cells: List<String>,
    colWidths: List<Dp>,
    colCount: Int,
    backgroundColor: Color,
    colDividerColor: Color,
    isHeader: Boolean
) {
    Row(
        modifier = Modifier
            .background(backgroundColor)
            .height(IntrinsicSize.Min)
    ) {
        repeat(colCount) { colIdx ->
            val cell = cells.getOrNull(colIdx) ?: ""
            Text(
                text = cell,
                style = if (isHeader) MaterialTheme.typography.labelSmall
                        else MaterialTheme.typography.bodySmall,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                maxLines = if (isHeader) 2 else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(colWidths.getOrElse(colIdx) { 80.dp })
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
            if (colIdx < colCount - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colDividerColor)
                )
            }
        }
    }
}
