package com.nexus.filemanager.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: FileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .aspectRatio(0.9f)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))

                FileIcon(item = item, modifier = Modifier.size(48.dp))

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (item.formattedSize().isNotEmpty()) {
                    Text(
                        text = item.formattedSize(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
