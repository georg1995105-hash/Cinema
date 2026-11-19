package app.cinema.player

import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class ReleaseApk(val version: String, val url: String, val bytes: Long, val digest: String?)

internal object AppUpdates {
    private const val api = "https://api.github.com/repos/georg1995105-hash/Cinema/releases?per_page=40"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var latest by mutableStateOf<ReleaseApk?>(null); private set
    var checking by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var progress by mutableIntStateOf(-1); private set
    var message by mutableStateOf(""); private set
    var downloadedFile by mutableStateOf<File?>(null); private set
    private var lastCheckMs = 0L
    private const val checkIntervalMs = 30L * 60L * 1000L
    private var downloadTask: Job? = null

    fun includeTestBuilds(context: Context): Boolean {
        val current = context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName.orEmpty()
        return context.getSharedPreferences("cinema_local_v1", Context.MODE_PRIVATE)
            .getBoolean("include_test_updates_v1", UpdateReleaseSelector.isTestBuild(current))
    }

    fun setIncludeTestBuilds(context: Context, include: Boolean) {
        context.getSharedPreferences("cinema_local_v1", Context.MODE_PRIVATE)
            .edit().putBoolean("include_test_updates_v1", include).apply()
        lastCheckMs = 0L
        check(context, force = true)
    }

    fun check(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (checking || downloading || (!force && lastCheckMs > 0L &&
                now - lastCheckMs < checkIntervalMs)) return
        checking = true
        message = "Проверяем обновления"
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetch(context) }
                lastCheckMs = SystemClock.elapsedRealtime()
                if (release?.version != latest?.version) {
                    downloadedFile?.delete()
                    downloadedFile = null
                    progress = -1
                }
                latest = release
                message = if (release == null) "Установлена последняя доступная версия"
                    else "Доступна версия " + release.version
                // OTA: download in-app once a tested release is available. Installation
                // always requires explicit user confirmation in the Android installer.
                if (release != null && downloadedFile == null) download(context)
            } catch (_: Exception) {
                message = "Не удалось проверить обновления"
            } finally {
                checking = false
            }
        }
    }

    private fun fetch(context: Context): ReleaseApk? {
        val current = context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName ?: "0.0.0"
        val connection = URL(api).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Cinema-Android")
        try {
            if (connection.responseCode != 200) error("Недоступен список версий")
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return UpdateReleaseSelector.select(releases, current, includeTestBuilds(context))
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context) {
        val release = latest ?: return
        if (downloading) return
        downloading = true; progress = 0; downloadedFile = null; message = "Загрузка обновления"
        downloadTask = scope.launch {
            val folder = File(context.cacheDir, "updates").apply { mkdirs() }
            val destination = File(folder, "Cinema-" + release.version + ".apk")
            try {
                withContext(Dispatchers.IO) {
                    val c = URL(release.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 12000; c.readTimeout = 20000
                    try {
                        if (c.responseCode != 200) error("Нет файла")
                        val total = release.bytes.takeIf { it > 0 } ?: c.contentLengthLong
                        val md = MessageDigest.getInstance("SHA-256")
                        var written = 0L
                        c.inputStream.use { src ->
                            destination.outputStream().use { dest ->
                                val buf = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = src.read(buf)
                                    if (count < 0) break
                                    dest.write(buf, 0, count)
                                    md.update(buf, 0, count)
                                    written += count
                                    val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 99) else -1
                                    if (percent != progress) withContext(Dispatchers.Main) { progress = percent }
                                }
                            }
                        }
                        if (written < 1024 || (release.bytes > 0 && release.bytes != written)) error("Неполный файл")
                        val hash = md.digest().joinToString("") { "%02x".format(it) }
                        if (release.digest != null && !hash.equals(release.digest, true)) error("Проверка суммы не пройдена")
                    } finally { c.disconnect() }
                }
                if (!checkPackage(context, destination, release.version)) {
                    message = "Пакет или версия обновления не соответствуют приложению"
                    destination.delete()
                } else if (!checkSignature(context, destination)) {
                    message = "Подписи различаются. Старые испытательные версии нельзя обновить поверх установки."
                    destination.delete()
                } else {
                    downloadedFile = destination
                    progress = 100
                    message = "Файл проверен и готов к установке"
                }
            } catch (_: CancellationException) {
                destination.delete(); message = "Загрузка отменена"
            } catch (_: Exception) {
                destination.delete(); message = "Не удалось скачать или проверить обновление"
            } finally { downloading = false }
        }
    }

    fun cancel() { downloadTask?.cancel() }

    private fun checkPackage(context: Context, file: File, expectedVersion: String): Boolean = runCatching {
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return@runCatching false
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        archive.packageName == context.packageName &&
            archive.versionName == expectedVersion &&
            AppVersionComparator.isNewer(archive.versionName.orEmpty(), installed.versionName.orEmpty()) &&
            PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun checkSignature(context: Context, file: File): Boolean = runCatching {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val old = context.packageManager.getPackageInfo(context.packageName, flags).signingInfo
            ?.apkContentsSigners ?: return@runCatching false
        val next = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.signingInfo
            ?.apkContentsSigners ?: return@runCatching false
        old.any { x -> next.any { y -> x.toByteArray().contentEquals(y.toByteArray()) } }
    }.getOrDefault(false)

    fun install(context: Context) {
        val file = downloadedFile ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                message = "Разрешите установку и снова нажмите «Установить»"
                return
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".updatefiles", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { message = "Не удалось открыть установщик Android" }
    }
}

@Composable
internal fun UpdatesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("Обновления", "Проверка новых версий")
        Spacer(Modifier.height(20.dp))
        Text("Версия " + current, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        var previewUpdates by remember(context) {
            mutableStateOf(AppUpdates.includeTestBuilds(context))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Тестовые обновления", modifier = Modifier.weight(1f))
            Switch(checked = previewUpdates, onCheckedChange = {
                previewUpdates = it
                AppUpdates.setIncludeTestBuilds(context, it)
            })
        }
        Text(AppUpdates.message.ifEmpty { "Проверка выполняется автоматически." }, color = appMuted)
        Spacer(Modifier.height(18.dp))
        if (AppUpdates.downloading) {
            if (AppUpdates.progress >= 0) {
                LinearProgressIndicator(
                    progress = { AppUpdates.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(AppUpdates.progress.toString() + " %", color = appBlue)
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { AppUpdates.cancel() }) { Text("Отменить") }
        } else if (AppUpdates.downloadedFile != null) {
            Button(onClick = { AppUpdates.install(context) }) { Text("Установить обновление") }
        } else if (AppUpdates.latest != null) {
            Button(onClick = { AppUpdates.download(context) }) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Скачать версию " + AppUpdates.latest!!.version)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(enabled = !AppUpdates.checking && !AppUpdates.downloading,
            onClick = { AppUpdates.check(context, true) }) {
            Text(if (AppUpdates.checking) "Проверка…" else "Проверить обновления")
        }
        Spacer(Modifier.height(22.dp))
        Text("Загрузка автоматическая. Установку подтверждает Android.",
            color = appMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun UpdateNotice(show: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ignored by remember { mutableStateOf<String?>(null) }
    val release = AppUpdates.latest
    if (show && release != null && release.version != ignored) AlertDialog(
        onDismissRequest = { ignored = release.version },
        title = { Text("Доступно обновление") },
        text = {
            Column {
                Text("Новая версия " + release.version)
                Spacer(Modifier.height(8.dp))
                if (AppUpdates.message.isNotBlank() &&
                    !AppUpdates.message.startsWith("Доступна версия ")) {
                    Spacer(Modifier.height(8.dp))
                    Text(AppUpdates.message, color = appMuted)
                }
                if (AppUpdates.downloading) {
                    Spacer(Modifier.height(12.dp))
                    if (AppUpdates.progress >= 0) {
                        LinearProgressIndicator(progress = { AppUpdates.progress / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text(AppUpdates.progress.toString() + " %")
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when {
                AppUpdates.downloadedFile != null -> TextButton(
                    onClick = { AppUpdates.install(context) }) { Text("Установить") }
                AppUpdates.downloading -> TextButton(onClick = { AppUpdates.cancel() }) {
                    Text("Отменить")
                }
                else -> TextButton(onClick = { AppUpdates.download(context) }) { Text("Скачать") }
            }
        },
        dismissButton = {
            TextButton(onClick = { ignored = release.version }) { Text("Позже") }
        }
    )
}
