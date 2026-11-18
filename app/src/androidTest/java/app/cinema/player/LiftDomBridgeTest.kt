package app.cinema.player

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A first-party DOM-shaped fixture running in actual Android WebView.
 * This tests browser interoperability; it does NOT claim that Lift grants
 * video playback or that external website markup is a stable API.
 */
class LiftDomBridgeTest {
    @Test fun searchFieldReceivesTextAndExactResultLinkIsRead() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        val address = "https://liftw.ws/details.html?id=18899&name=%D0%9A%D1%80%D0%B0%D1%81%D0%BD%D0%B0%D1%8F%20%D0%BF%D0%B0%D0%BB%D0%B0%D1%82%D0%BA%D0%B0&year=1969"
        val html = """
            <html lang="ru"><body>
              <input id="q" placeholder="Поиск" />
              <div id="results"></div>
              <script>
                document.getElementById('q').addEventListener('input', function(e) {
                  const results = document.getElementById('results');
                  results.innerHTML = '';
                  const link = document.createElement('a');
                  link.href = '$address';
                  link.textContent = 'SD Красная палатка';
                  results.appendChild(link);
                });
              </script>
            </body></html>
        """.trimIndent()
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
                loadDataWithBaseURL(LiftWebSource.SEARCH, html, "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("Fixture did not load", loaded.await(15, TimeUnit.SECONDS))
            val searchResult = AtomicReference<String>()
            val inputDone = CountDownLatch(1)
            instrumentation.runOnMainSync {
                view.evaluateJavascript(LiftMatchPolicy.searchScript("Красная палатка")) {
                    searchResult.set(it)
                    inputDone.countDown()
                }
            }
            assertTrue("Input JS callback missing", inputDone.await(10, TimeUnit.SECONDS))
            assertEquals("true", searchResult.get())

            val links = AtomicReference<String>()
            val resultDone = CountDownLatch(1)
            instrumentation.runOnMainSync {
                view.evaluateJavascript(LiftMatchPolicy.inspectResultsScript) {
                    links.set(it)
                    resultDone.countDown()
                }
            }
            assertTrue("DOM result callback missing", resultDone.await(10, TimeUnit.SECONDS))
            val matches = LiftMatchPolicy.parseBrowserResults(links.get())
            assertEquals(1, matches.size)
            assertEquals(address, matches.single().url)
            assertEquals(1969, matches.single().year)
            assertEquals(address,
                LiftMatchPolicy.singleExact("Красная палатка", 1969, matches)?.url)
        } finally {
            instrumentation.runOnMainSync { view.destroy() }
        }
    }
}
