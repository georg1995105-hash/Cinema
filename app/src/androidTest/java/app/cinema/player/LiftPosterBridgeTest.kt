package app.cinema.player

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** First-party DOM fixture that matches Lift's actual og:image and data-src fields. */
class LiftPosterBridgeTest {
    @Test fun recoversLiftCoverFromNameMetaAndLazyImage() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        val url = "https://liftw.ws/details.html?id=383&name=Film"
        val html = """
          <html><head>
            <meta name="og:image" content="https://img.liftw.ws/real-poster-token">
          </head><body>
            <h1>Фильм</h1>
            <img data-src="https://img.liftw.ws/lazy-poster-token"
                 src="https://liftw.ws/assets/img/no-poster.svg">
          </body></html>
        """.trimIndent()
        lateinit var view: WebView
        inst.runOnMainSync {
            view = WebView(inst.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
                loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("DOM did not load", loaded.await(12, TimeUnit.SECONDS))
            val captured = AtomicReference<String>()
            val done = CountDownLatch(1)
            inst.runOnMainSync {
                view.evaluateJavascript(LiftPosterMetadata.script) {
                    captured.set(it)
                    done.countDown()
                }
            }
            assertTrue("JS callback missing", done.await(10, TimeUnit.SECONDS))
            val movie = requireNotNull(LibraryCardMetadata.parse(captured.get(), url))
            assertEquals("Фильм", movie.title)
            assertEquals("https://img.liftw.ws/real-poster-token", movie.poster)
            assertTrue(movie.posterAlternatives.contains("https://img.liftw.ws/lazy-poster-token"))
            assertFalse(movie.posterAlternatives.any { it.contains("no-poster.svg") })
        } finally {
            inst.runOnMainSync { view.destroy() }
        }
    }
}
