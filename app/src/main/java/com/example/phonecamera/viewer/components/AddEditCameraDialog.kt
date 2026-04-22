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
    // Allow hostname (letters/numbers/dots/hyphens) or IPv4
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

    // Validation state
    val nameError = name.isBlank()
    val hostError = host.isNotBlank() && !isValidIp(host)
    val portError = port.isNotBlank() && !isValidPort(port)
    val isFormValid = !nameError && host.isNotBlank() && isValidIp(host) && !portError

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NavyCard,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row {
                Text(
                    text = if (initialConfig != null) "Sửa Camera ${slotIndex + 1}" else "Thêm Camera ${slotIndex + 1}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ─── Name ───
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Tên camera",
                    leadingIcon = Icons.Outlined.Label,
                    isError = nameError && name.isNotBlank(),
                    errorMessage = "Tên không được để trống"
                )

                HorizontalDivider(color = DividerColor)

                Text(
                    text = "KẾT NỐI",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                    fontWeight = FontWeight.SemiBold
                )

                // ─── Host / IP ───
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

                // ─── Port ───
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

                // ─── Auth (optional) ───
                Text(
                    text = "XÁC THỰC (tuỳ chọn)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                    fontWeight = FontWeight.SemiBold
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
                                tint = TextSecondary
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
                    containerColor = CyanNeon,
                    contentColor = NavyDeep,
                    disabledContainerColor = CyanNeon.copy(alpha = 0.3f),
                    disabledContentColor = NavyDeep.copy(alpha = 0.5f)
                )
            ) {
                Text("Lưu", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = RedError)
                    ) {
                        Text("Xóa")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text("Huỷ", color = TextSecondary)
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
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = TextHint) }
            } else null,
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
            },
            trailingIcon = trailingIcon,
            isError = isError,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanNeon,
                unfocusedBorderColor = DividerColor,
                errorBorderColor = RedError,
                focusedLabelColor = CyanNeon,
                unfocusedLabelColor = TextSecondary,
                errorLabelColor = RedError,
                focusedLeadingIconColor = CyanNeon,
                unfocusedLeadingIconColor = TextSecondary,
                cursorColor = CyanNeon,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                errorTextColor = TextPrimary,
                focusedContainerColor = NavySurface,
                unfocusedContainerColor = NavySurface,
                errorContainerColor = NavySurface
            ),
            shape = RoundedCornerShape(10.dp)
        )
        AnimatedVisibility(visible = isError && errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = RedError,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}
