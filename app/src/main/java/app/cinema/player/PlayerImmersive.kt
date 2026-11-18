package app.cinema.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * A single lifecycle-scoped fullscreen controller for native Media3 and
 * HTML5 custom video views. Restores the Activity orientation on exit.
 */
@Composable
internal fun PlayerImmersiveLifecycle(key: Any?) {
    val context = LocalContext.current
    DisposableEffect(context, key) {
        val activity = findPlayerActivity(context)
        val root = activity?.window?.decorView
        val oldFlags = root?.systemUiVisibility ?: 0
        val oldOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        val oldBehavior = controller?.systemBarsBehavior

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        root?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            root?.systemUiVisibility = oldFlags
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (oldBehavior != null) controller?.systemBarsBehavior = oldBehavior
            activity?.requestedOrientation = oldOrientation
        }
    }
}

private fun findPlayerActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findPlayerActivity(context.baseContext)
    else -> null
}
