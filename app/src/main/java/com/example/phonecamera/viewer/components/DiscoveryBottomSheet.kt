package com.example.phonecamera.viewer.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phonecamera.data.nsd.DiscoveredCamera
import com.example.phonecamera.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryBottomSheet(
    isScanning: Boolean,
    discovered: List<DiscoveredCamera>,
    occupiedSlots: Set<String>,       // rtspUrl already in use
    onAddCamera: (DiscoveredCamera) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NavyCard,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(DividerColor, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // ─── Header ───
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.WifiFind,
                    contentDescription = null,
                    tint = CyanNeon,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Camera trên mạng LAN",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isScanning) "Đang tìm kiếm..." else "${discovered.size} camera tìm thấy",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isScanning) CyanNeon else TextSecondary
                    )
                }
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = CyanNeon,
                        strokeWidth = 2.dp
                    )
                }
            }

            HorizontalDivider(color = DividerColor)
            Spacer(modifier = Modifier.height(12.dp))

            // ─── Camera list ───
            if (discovered.isEmpty()) {
                EmptyDiscoveryState(isScanning)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(discovered, key = { it.serviceId }) { camera ->
                        val alreadyAdded = camera.rtspUrl in occupiedSlots
                        DiscoveredCameraRow(
                            camera = camera,
                            alreadyAdded = alreadyAdded,
                            onAdd = { onAddCamera(camera) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredCameraRow(
    camera: DiscoveredCamera,
    alreadyAdded: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NavySurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Camera icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (alreadyAdded) GreenOnline.copy(alpha = 0.1f)
                    else CyanNeon.copy(alpha = 0.1f),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = if (alreadyAdded) GreenOnline else CyanNeon,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = camera.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = "${camera.host}:${camera.port}",
                style = MaterialTheme.typography.bodySmall,
                color = TextHint,
                fontSize = 11.sp
            )
        }

        // Add button
        if (alreadyAdded) {
            Box(
                modifier = Modifier
                    .background(GreenOnline.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("Đã thêm", style = MaterialTheme.typography.labelSmall, color = GreenOnline)
            }
        } else {
            FilledTonalButton(
                onClick = onAdd,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CyanNeon.copy(alpha = 0.15f),
                    contentColor = CyanNeon
                )
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Thêm", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryState(isScanning: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isScanning) Icons.Outlined.WifiFind else Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isScanning) "Đang quét mạng LAN..." else "Không tìm thấy camera nào",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (isScanning)
                "Đảm bảo điện thoại camera đang phát"
            else
                "Hãy bấm Bắt đầu phát trên điện thoại camera\nvà thử lại",
            style = MaterialTheme.typography.bodySmall,
            color = TextHint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
