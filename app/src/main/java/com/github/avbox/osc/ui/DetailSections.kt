package com.github.avbox.osc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.components.marqueeOnOverflow

@Composable
internal fun SectionTitleIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SectionTitleIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

internal const val DetailCardAlpha = 0.5f

@Composable
internal fun detailCardColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = DetailCardAlpha)

@Composable
internal fun detailChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = detailCardColor(),
    selectedContainerColor = detailCardColor(),
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
internal fun detailChipBorder(selected: Boolean) = if (selected) {
    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
} else {
    BorderStroke(0.dp, Color.Transparent)
}

internal val DetailItemHeight = 70.dp
internal val DetailItemMinWidth = 160.dp
internal val DetailItemWidth = 160.dp

// 竖屏/手机（非 TV flatHero）用 OK影视式小标签；TV 横屏仍保留适合遥控器的大卡片
internal val DetailChipHeight = 36.dp
internal val DetailChipMinWidth = 56.dp
internal val DetailChipMaxWidth = 160.dp

@Composable
internal fun detailCompactChips(): Boolean {
    val cfg = LocalConfiguration.current
    return !flatHeroLayout(cfg.smallestScreenWidthDp, cfg.screenWidthDp, cfg.screenHeightDp)
}


@Composable
internal fun Modifier.detailGlass(shape: Shape): Modifier = this
    .clip(shape)
    // 卡片式详情页里 chip 落在白色卡片上，改实色浅底 + 浅描边才有边界
    .background(MaterialTheme.colorScheme.surfaceContainerLow)
    .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        shape = shape,
    )

@Composable
internal fun DetailItemCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = detailCompactChips()
    val shape = RoundedCornerShape(if (compact) 20.dp else 16.dp)
    Column(
        modifier = modifier
            .height(if (compact) DetailChipHeight else DetailItemHeight)
            .defaultMinSize(minWidth = if (compact) DetailChipMinWidth else DetailItemMinWidth)
            .detailGlass(shape)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 12.dp else 14.dp,
                vertical = if (compact) 4.dp else 12.dp,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun SourceCard(name: String, selected: Boolean, onClick: () -> Unit) {
    val compact = detailCompactChips()
    DetailItemCard(
        selected = selected,
        onClick = onClick,
        modifier = if (compact) {
            Modifier.widthIn(min = DetailChipMinWidth, max = DetailChipMaxWidth)
        } else {
            Modifier.width(DetailItemWidth)
        },
    ) {
        Text(
            text = name,
            style = if (compact) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(
                    if (compact) {
                        Modifier.widthIn(max = DetailChipMaxWidth)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .marqueeOnOverflow(),
        )
    }
}

@Composable
internal fun SourceSection(vm: DetailViewModel, currentSourceName: String?) {
    val sourceChips by vm.sourceChips.collectAsState()
    val sourcesSearching by vm.sourcesSearching.collectAsState()
    if (!sourcesSearching && sourceChips.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(currentSourceName) {
        if (currentSourceName != null) listState.scrollToItem(0)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_switch_source))
            Text(
                text = stringResource(R.string.detail_switch_source),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            if (sourcesSearching) {
                Text(
                    text = stringResource(R.string.detail_finding_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(
                if (detailCompactChips()) 8.dp else 10.dp,
            ),
        ) {
            if (currentSourceName != null) {
                item(key = "current") {
                    SourceCard(
                        name = currentSourceName,
                        selected = true,
                        onClick = {},
                    )
                }
            }
            itemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                SourceCard(
                    name = chip.name,
                    selected = false,
                    onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                )
            }
        }
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

internal fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}
