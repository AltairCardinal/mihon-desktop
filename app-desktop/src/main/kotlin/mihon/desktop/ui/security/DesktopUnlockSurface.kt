package mihon.desktop.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mihon.desktop.security.DesktopAppLock
import mihon.domain.security.AuthenticationResult
import tachiyomi.i18n.MR

@Composable
fun DesktopProtectedRoot(
    appLock: DesktopAppLock,
    protectedContent: @Composable () -> Unit,
) {
    val lockState by appLock.state.collectAsState()
    if (lockState.requiresUnlock) {
        DesktopUnlockSurface(appLock::authenticate)
    } else {
        protectedContent()
    }
}

@Composable
fun DesktopUnlockSurface(authenticate: (CharArray?) -> AuthenticationResult) {
    var passphrase by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<AuthenticationResult?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(MR.strings.desktop_unlock_title.localized(), style = MaterialTheme.typography.headlineSmall)
                Text(MR.strings.desktop_unlock_summary.localized())
                DesktopPasswordField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = MR.strings.desktop_unlock_passphrase.localized(),
                )
                feedback?.takeIf { it != AuthenticationResult.Success }?.let {
                    Text(unlockFeedback(it), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        val input = passphrase.toCharArray()
                        passphrase = ""
                        feedback = authenticate(input)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(MR.strings.desktop_unlock_action.localized())
                }
            }
        }
    }
}

@Composable
internal fun DesktopPasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun unlockFeedback(result: AuthenticationResult): String = when (result) {
    AuthenticationResult.Cancelled -> MR.strings.desktop_security_cancelled.localized()
    AuthenticationResult.Failed -> MR.strings.desktop_security_authentication_failed.localized()
    AuthenticationResult.Unavailable -> MR.strings.desktop_security_backend_unavailable.localized()
    AuthenticationResult.Error -> MR.strings.desktop_security_backend_error.localized()
    AuthenticationResult.Success -> MR.strings.desktop_security_saved.localized()
}
