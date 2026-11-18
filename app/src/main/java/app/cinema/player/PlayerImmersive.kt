package app.cinema.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun PlayerImmersiveLifecycle(key: Any?) {
    val context = LocalContext.current
    DisposableEffect(context, key) {
        val activity = findPlayerActivity(context)
        val root = activity?.window?.decorView
        val originalFlags = root?.systemUiVisibility ?: 0
        val originalOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        root?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        onDispose {
            root?.systemUiVisibility = originalFlags
            activity?.requestedOrientation = originalOrientation
        }
    }
}

private fun findPlayerActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findPlayerActivity(context.baseContext)
    else -> null
}
