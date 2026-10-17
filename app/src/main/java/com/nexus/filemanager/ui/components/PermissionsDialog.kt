package com.nexus.filemanager.ui.components

import com.nexus.filemanager.str
import com.nexus.filemanager.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileItem

@Composable
fun PermissionsDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onApply: (octal: String) -> Unit
) {
    // Initial parsing of permissions string (e.g. "rwxr-xr-x" or "-rw-r--r--")
    val raw = item.permissions.takeLast(9).padEnd(9, '-')

    var ownerR by remember { mutableStateOf(raw.getOrNull(0) == 'r') }
    var ownerW by remember { mutableStateOf(raw.getOrNull(1) == 'w') }
    var ownerX by remember { mutableStateOf(raw.getOrNull(2) == 'x') }

    var groupR by remember { mutableStateOf(raw.getOrNull(3) == 'r') }
    var groupW by remember { mutableStateOf(raw.getOrNull(4) == 'w') }
    var groupX by remember { mutableStateOf(raw.getOrNull(5) == 'x') }

    var otherR by remember { mutableStateOf(raw.getOrNull(6) == 'r') }
    var otherW by remember { mutableStateOf(raw.getOrNull(7) == 'w') }
    var otherX by remember { mutableStateOf(raw.getOrNull(8) == 'x') }

    val ownerVal = (if (ownerR) 4 else 0) + (if (ownerW) 2 else 0) + (if (ownerX) 1 else 0)
    val groupVal = (if (groupR) 4 else 0) + (if (groupW) 2 else 0) + (if (groupX) 1 else 0)
    val otherVal = (if (otherR) 4 else 0) + (if (otherW) 2 else 0) + (if (otherX) 1 else 0)
    val octalString = "0$ownerVal$groupVal$otherVal"

    fun setOctal(o: Int, g: Int, oth: Int) {
        ownerR = (o and 4) != 0
        ownerW = (o and 2) != 0
        ownerX = (o and 1) != 0

        groupR = (g and 4) != 0
        groupW = (g and 2) != 0
        groupX = (g and 1) != 0

        otherR = (oth and 4) != 0
        otherW = (oth and 2) != 0
        otherX = (oth and 1) != 0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(str(R.string.permissions_chmod))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Octal Display Banner
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = str(R.string.octal_code),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = octalString,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Table Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = str(R.string.perm_class), modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(text = str(R.string.r_read), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(text = str(R.string.w_write), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(text = str(R.string.x_exec), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                // Owner Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(str(R.string.owner), modifier = Modifier.weight(1.5f), fontSize = 13.sp)
                    Checkbox(checked = ownerR, onCheckedChange = { ownerR = it }, modifier = Modifier.weight(1f))
                    Checkbox(checked = ownerW, onCheckedChange = { ownerW = it }, modifier = Modifier.weight(1f))
                    Checkbox(checked = ownerX, onCheckedChange = { ownerX = it }, modifier = Modifier.weight(1f))
                }

                // Group Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(str(R.string.group), modifier = Modifier.weight(1.5f), fontSize = 13.sp)
                    Checkbox(checked = groupR, onCheckedChange = { groupR = it }, modifier = Modifier.weight(1f))
                    Checkbox(checked = groupW, onCheckedChange = { groupW = it }, modifier = Modifier.weight(1f))
                    Checkbox(checked = groupX, onCheckedChange = { groupX = it }, modifier = Modifier.weight(1f))
                }

                // Others Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(str(R.string.others), modifier = Modifier.weight(1.5f), fontSize = 13.sp)
                    Checkbox(checked = otherR, onCheckedChange = { otherR = it }, modifier = Modifier.weight(1f))
                    Checkbox(checked = otherW, onCheckedChange = { otherW = it }, modifier = Modifier.weight(1f))
                    Checkbox(checked = otherX, onCheckedChange = { otherX = it }, modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Presets
                Text(str(R.string.presets), style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = octalString == "0644",
                        onClick = { setOctal(6, 4, 4) },
                        label = { Text("0644") }
                    )
                    FilterChip(
                        selected = octalString == "0755",
                        onClick = { setOctal(7, 5, 5) },
                        label = { Text("0755") }
                    )
                    FilterChip(
                        selected = octalString == "0777",
                        onClick = { setOctal(7, 7, 7) },
                        label = { Text("0777") }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(octalString) }) {
                Text(str(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(str(R.string.cancel))
            }
        }
    )
}
