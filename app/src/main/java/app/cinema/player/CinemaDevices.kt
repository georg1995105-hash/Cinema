package app.cinema.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** One application / one APK. Only navigation density depends on device class. */
internal enum class CinemaDevice { PHONE, TABLET, TV }

internal object CinemaDevices {
    fun classify(isTelevision: Boolean, smallestWidthDp: Int): CinemaDevice = when {
        isTelevision -> CinemaDevice.TV
        smallestWidthDp >= 600 -> CinemaDevice.TABLET
        else -> CinemaDevice.PHONE
    }

    fun detect(context: Context, configuration: Configuration): CinemaDevice {
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTv = manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return classify(isTv, configuration.smallestScreenWidthDp)
    }

    fun minColumns(device: CinemaDevice) = when (device) {
        CinemaDevice.PHONE -> 2
        CinemaDevice.TABLET -> 3
        CinemaDevice.TV -> 5
    }

    fun maxColumns(device: CinemaDevice) = when (device) {
        CinemaDevice.PHONE -> 3
        CinemaDevice.TABLET -> 6
        CinemaDevice.TV -> 7
    }

    fun columns(device: CinemaDevice, wanted: Int, widthDp: Int): Int {
        val preferred = wanted.coerceIn(minColumns(device), maxColumns(device))
        val fit = when (device) {
            CinemaDevice.PHONE -> (widthDp / 128).coerceAtLeast(2)
            CinemaDevice.TABLET -> (widthDp / 148).coerceAtLeast(3)
            CinemaDevice.TV -> (widthDp / 155).coerceAtLeast(5)
        }
        return preferred.coerceAtMost(fit).coerceAtLeast(minColumns(device))
    }

    fun isTv(device: CinemaDevice): Boolean = device == CinemaDevice.TV
}
