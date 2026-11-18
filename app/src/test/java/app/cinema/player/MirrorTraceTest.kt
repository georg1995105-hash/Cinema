package app.cinema.player

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class MirrorTraceTest {
    @Test fun reportNeverCopiesPathsQueryTokensOrCookies() {
        MirrorTrace.clear()
        MirrorTrace.add("ajax", "https://film.example/series/125-title.html?token=VERY_SECRET&user=123",
            status = 403, result = "response")
        MirrorTrace.add("media", "https://cdn.example/m3u8/manifest.m3u8?authorization=SECRET",
            status = 200, result = "response")
        val report = MirrorTrace.report()
        assertTrue(report.contains("film.example"))
        assertTrue(report.contains("cdn.example"))
        assertTrue(report.contains("HTTP 403"))
        assertFalse(report.contains("VERY_SECRET"))
        assertFalse(report.contains("SECRET"))
        assertFalse(report.contains("title.html"))
        assertFalse(report.contains("manifest.m3u8"))
        assertFalse(report.contains("token="))
        assertFalse(report.contains("user=123"))
        assertFalse(report.contains("authorization="))
    }

    @Test fun networkFailuresHaveStableCategoriesWithoutExceptionBodies() {
        assertEquals("timeout", MirrorTrace.networkFailure(SocketTimeoutException("PRIVATE URL")))
        assertEquals("dns", MirrorTrace.networkFailure(UnknownHostException("PRIVATE URL")))
        assertEquals("tls", MirrorTrace.networkFailure(SSLHandshakeException("PRIVATE URL")))
        MirrorTrace.clear()
        MirrorTrace.add("invalid\nmalicious-token", "https://cdn.example/video.ts?private=x",
            result = "error private=x")
        assertFalse(MirrorTrace.report().contains("malicious-token"))
        assertFalse(MirrorTrace.report().contains("private=x"))
    }

    @Test fun capturesLastSixtyBoundedRecords() {
        MirrorTrace.clear()
        repeat(90) {
            MirrorTrace.add("media", "https://cdn.example/file$it.ts", status = 206)
        }
        val lines = MirrorTrace.report().lines()
        assertEquals(62, lines.size)
        assertEquals("media", MirrorTrace.stage("https://cdn.example/file.m3u8?token=secret"))
        assertEquals("ajax", MirrorTrace.stage("https://mirror.example/ajax/get_cdn_series/"))
        assertEquals("person", MirrorTrace.stage("https://mirror.example/ajax/person_info/"))
    }
}
