package app.cinema.player

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TvLauncherManifestTest {
    @Test fun sameApplicationPackageIsVisibleToTvLauncher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val television = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(television, 0)
        assertTrue(
            "Cinema must be launchable on TV without a separate app",
            matches.any { it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name.endsWith(".MainActivity") }
        )
    }
}
