package app.cinema.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Native account screen. Does not display the mirror homepage in a WebView.
 * Only registration (requiring the site's own validation) explicitly opens its page.
 */
@Composable
internal fun MirrorAccountScreen(
    sources: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onSources: () -> Unit,
    onRegistration: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val gateway = remember(context) { MirrorAccountGateway(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var login by remember(selected) { mutableStateOf("") }
    var password by remember(selected) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(selected) { mutableStateOf("") }
    var signedIn by remember(selected) { mutableStateOf(selected?.let(gateway::hasSession) ?: false) }
    var sourceMenu by remember { mutableStateOf(false) }
    val sourceName = selected?.let {
        runCatching { URI(it).host }.getOrNull()
    } ?: "Зеркало не выбрано"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeading("Аккаунт и сеть", back = onBack)
        Spacer(Modifier.height(2.dp))

        Surface(color = appPanel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Зеркало", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Public, contentDescription = null, tint = appBlue)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        TextButton(
                            onClick = { sourceMenu = true },
                            enabled = sources.isNotEmpty()
                        ) {
                            Text(sourceName, maxLines = 1)
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = sourceMenu,
                            onDismissRequest = { sourceMenu = false }
                        ) {
                            sources.forEach { url ->
                                DropdownMenuItem(
                                    text = { Text(runCatching { URI(url).host }.getOrDefault(url)) },
                                    leadingIcon = {
                                        if (url == selected) Icon(Icons.Outlined.Check,
                                            contentDescription = null, tint = appBlue)
                                    },
                                    onClick = { sourceMenu = false; onSelect(url) }
                                )
                            }
                        }
                    }
                    TextButton(onClick = onSources) { Text("Изменить") }
                }
                Text("Адрес выбирается вами и остаётся на устройстве.",
                    color = appMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(color = appPanel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, contentDescription = null, tint = appBlue)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text("Профиль", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (signedIn) "Сеанс сохранён на устройстве" else "Гостевой режим",
                            color = appMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selected == null) {
                    Text("Добавьте зеркало, чтобы войти в свой аккаунт.",
                        color = appMuted, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onSources) { Text("Добавить зеркало") }
                } else if (signedIn) {
                    Text("Вход выполнен через выбранное зеркало. Приложение использует его сеанс для каталога и воспроизведения.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        gateway.forgetLocalSession(selected)
                        signedIn = false
                        login = ""
                        password = ""
                        message = "Сеанс удалён с устройства"
                    }) {
                        Icon(Icons.Outlined.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Выйти")
                    }
                } else {
                    Text("Войти в аккаунт", fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it; message = "" },
                        label = { Text("Логин или почта") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; message = "" },
                        label = { Text("Пароль") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (visible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(if (visible) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    contentDescription = if (visible) "Скрыть пароль"
                                        else "Показать пароль")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            enabled = !busy && login.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = gateway.signIn(selected, login, password)
                                    busy = false
                                    signedIn = result.success
                                    message = result.message
                                    password = ""
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (busy) CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                            ) else Text("Войти")
                        }
                        OutlinedButton(
                            onClick = { onRegistration(selected) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Регистрация") }
                    }
                    Text(
                        "Пароль отправляется только выбранному сайту по HTTPS. " +
                            "Он не сохраняется приложением.",
                        color = appMuted, style = MaterialTheme.typography.bodySmall
                    )
                }
                if (message.isNotBlank()) {
                    Text(message, color = if (signedIn) appBlue else
                        MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Surface(color = appPanel, shape = RoundedCornerShape(18.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = appBlue)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("История просмотров", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("Хранится на устройстве. Синхронизация с аккаунтом зеркала пока недоступна.",
                        color = appMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
