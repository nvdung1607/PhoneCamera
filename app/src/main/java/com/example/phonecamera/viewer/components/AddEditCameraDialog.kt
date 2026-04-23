package com.example.phonecamera.viewer.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.phonecamera.data.CameraConfig
import com.example.phonecamera.ui.theme.*

private fun isValidIp(input: String): Boolean {
    if (input.isBlank()) return false
    val hostnameRegex = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?)*$""")
    val ipv4Regex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    return hostnameRegex.matches(input) || ipv4Regex.matches(input)
}

private fun isValidPort(input: String): Boolean {
    val port = input.toIntOrNull() ?: return false
    return port in 1..65535
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    val nameError = name.isBlank()
    val hostError = host.isNotBlank() && !isValidIp(host)
    val portError = port.isNotBlank() && !isValidPort(port)
    val isFormValid = !nameError && host.isNotBlank() && isValidIp(host) && !portError

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
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Tên camera",
                    leadingIcon = Icons.Outlined.Label,
                    isError = nameError && name.isNotBlank(),
                    errorMessage = "Tên không được để trống"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                Text(
                    text = "KẾT NỐI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                AppTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = "Địa chỉ IP hoặc Hostname",
                    placeholder = "VD: 192.168.1.15",
                    leadingIcon = Icons.Outlined.Router,
                    isError = hostError,
                    errorMessage = "Định dạng IP/hostname không hợp lệ",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                AppTextField(
                    value = port,
                    onValueChange = { if (it.length <= 5) port = it.filter(Char::isDigit) },
                    label = "Cổng (Port)",
                    placeholder = "8080",
                    leadingIcon = Icons.Outlined.Settings,
                    isError = portError,
                    errorMessage = "Cổng hợp lệ từ 1 đến 65535",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    text = "XÁC THỰC (tuỳ chọn)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                AppTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Tên đăng nhập",
                    leadingIcon = Icons.Outlined.Person
                )

                AppTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Mật khẩu",
                    leadingIcon = Icons.Outlined.Lock,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        CameraConfig(
                            id = slotIndex,
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 8080,
                            username = username.trim(),
                            password = password
                        )
                    )
                },
                enabled = isFormValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Lưu", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Xóa")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text("Huỷ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder) }
            } else null,
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
            },
            trailingIcon = trailingIcon,
            isError = isError,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
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
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                errorContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        AnimatedVisibility(visible = isError && errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
