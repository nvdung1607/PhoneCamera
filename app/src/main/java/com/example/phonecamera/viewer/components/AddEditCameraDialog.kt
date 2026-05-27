package com.example.phonecamera.viewer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.phonecamera.data.CameraConfig

private fun isValidHost(input: String): Boolean {
    if (input.isBlank()) return false
    val hostname = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?)*$""")
    val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    return hostname.matches(input) || ipv4.matches(input)
}

private fun isValidPort(input: String) = input.toIntOrNull()?.let { it in 1..65535 } ?: false

@Composable
fun AddEditCameraDialog(
    slotIndex: Int,
    initialConfig: CameraConfig?,
    onConfirm: (CameraConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "Camera ${slotIndex + 1}") }
    var host by remember { mutableStateOf(initialConfig?.host ?: "") }
    var port by remember { mutableStateOf(initialConfig?.port?.toString() ?: "8080") }
    var pinCode by remember { mutableStateOf(initialConfig?.pinCode ?: "") }

    val nameError = name.isBlank()
    val hostError = host.isNotBlank() && !isValidHost(host)
    val portError = port.isNotBlank() && !isValidPort(port)
    
    // Yêu cầu mã PIN 4 chữ số nếu là camera điện thoại
    val isPhoneCamera = initialConfig?.isPhoneCamera ?: false
    val pinError = pinCode.isNotBlank() && (pinCode.length != 4 || !pinCode.all { it.isDigit() })
    val isFormValid = !nameError && isValidHost(host) && !portError && (!isPhoneCamera || (pinCode.length == 4 && pinCode.all { it.isDigit() }))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = if (initialConfig != null) "Sửa Camera ${slotIndex + 1}" else "Thêm Camera ${slotIndex + 1}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CameraTextField(
                    value = name, onValueChange = { name = it },
                    label = "Tên camera", leadingIcon = Icons.Outlined.Label,
                    isError = nameError && name.isNotBlank(), errorMessage = "Tên không được để trống"
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                Text(
                    text = "KẾT NỐI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                CameraTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = "Địa chỉ IP hoặc Hostname",
                    placeholder = "VD: 192.168.1.15",
                    leadingIcon = Icons.Outlined.Router,
                    isError = hostError, errorMessage = "Định dạng IP/hostname không hợp lệ",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                CameraTextField(
                    value = port, onValueChange = { if (it.length <= 5) port = it.filter(Char::isDigit) },
                    label = "Cổng (Port)", placeholder = "8080",
                    leadingIcon = Icons.Outlined.Settings,
                    isError = portError, errorMessage = "Cổng hợp lệ từ 1 đến 65535",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                CameraTextField(
                    value = pinCode, onValueChange = { if (it.length <= 4) pinCode = it.filter(Char::isDigit) },
                    label = "Mã PIN bảo mật (4 chữ số)", placeholder = "VD: 1234",
                    leadingIcon = Icons.Outlined.Lock,
                    isError = pinError, errorMessage = "Mã PIN phải gồm đúng 4 chữ số",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onDelete != null) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Xóa", fontWeight = FontWeight.Bold)
                    }
                }
                
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("Hủy", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Button(
                    onClick = {
                        onConfirm(CameraConfig(
                            id = slotIndex,
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 8080,
                            isPhoneCamera = isPhoneCamera,
                            pinCode = pinCode
                        ))
                    },
                    enabled = isFormValid,
                    colors = ButtonDefaults.buttonColors(
                         containerColor = MaterialTheme.colorScheme.primary,
                         contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Lưu", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = null
    )
}

@Composable
private fun CameraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isError: Boolean = false,
    errorMessage: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    Column {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
            leadingIcon = leadingIcon?.let { { Icon(it, null, modifier = Modifier.size(20.dp)) } },
            isError = isError, singleLine = true,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        AnimatedVisibility(visible = isError && errorMessage.isNotEmpty()) {
            Text(errorMessage, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
    }
}
