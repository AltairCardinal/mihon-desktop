package mihon.desktop.ui.cloudflare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI

@Composable
fun CloudflareBypassDialog(
    url: String,
    onCookieSubmit: (cookieValue: String) -> Unit,
    onCancel: () -> Unit,
) {
    var cookieValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Cloudflare 验证") },
        text = {
            Column {
                Text("该源需要通过 Cloudflare 验证。请在浏览器中完成验证后，将 cf_clearance cookie 的值粘贴到下方。")
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(URI(url))
                        }
                    },
                ) {
                    Text("在浏览器中打开")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cookieValue,
                    onValueChange = { cookieValue = it },
                    label = { Text("cf_clearance 值") },
                    placeholder = { Text("粘贴 cookie 值...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onCookieSubmit(cookieValue.trim()) },
                    enabled = cookieValue.isNotBlank(),
                ) {
                    Text("确认")
                }
            }
        },
    )
}
