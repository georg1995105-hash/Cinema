package app.cinema.player

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Real HTTPS transport against a local mirror fixture, not a fabricated
 * UI health result. Root 200 + catalog 403 must never show "available".
 */
class CatalogProbeNetworkTest {
    @Test fun root200WithCatalog403IsNotReportedAsHealthy() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
                false
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>root</html>"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("<html>blocked catalog</html>"))
            server.start()
            val transport = MirrorHttp.client.newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .build()
            val result = runBlocking {
                SourceProbe.inspect(server.url("/").toString(), "Browser UA", transport)
            }
            assertFalse(result.available)
            assertTrue(result.message.contains("каталог ограничен"))
            assertTrue(result.message.contains("403"))
            assertEquals("/", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertEquals("/series/", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        }
    }

    @Test fun catalog200IsExplicitlyNotVideoAvailability() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost").build()
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
                false
            )
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val transport = MirrorHttp.client.newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .build()
            val result = runBlocking {
                SourceProbe.inspect(server.url("/").toString(), "Browser UA", transport)
            }
            assertTrue(result.available)
            assertTrue(result.message.contains("видео не проверено"))
        }
    }
}
