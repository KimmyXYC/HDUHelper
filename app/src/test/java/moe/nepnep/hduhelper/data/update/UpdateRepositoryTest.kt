package moe.nepnep.hduhelper.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private fun release(tag: String = "v1.2.0", assets: String = "HDUHelper-$tag.apk") =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[{"name":"$assets"}],"body":null}"""

    @Test fun versionsCompareNumericallyAndNormalizeCi() {
        val repository = UpdateRepository()
        assertNotNull(repository.parseRelease(release("v1.10.0"), "v1.9.0.abcdef0"))
        assertNull(repository.parseRelease(release(), "1.2.0"))
        assertNull(repository.parseRelease(release(), "v1.2.0.abcdef0"))
        assertNull(repository.parseRelease(release(), "1.3.0"))
        assertEquals("暂无更新说明", repository.parseRelease(release(), "1.0.0")?.notes)
        assertEquals("https://github.com/KimmyXYC/HDUHelper/releases/tag/v1.2.0", repository.parseRelease(release(), "1.0.0")?.url)
    }

    @Test fun malformedAndUnpublishedReleasesFail() {
        val repository = UpdateRepository()
        for (body in listOf("garbage", "{}", "[]", release("v1.2.0-beta"), release(assets = "source.zip"),
            release().replace("\"draft\":false", "\"draft\":true"),
            release().replace("\"prerelease\":false", "\"prerelease\":true"))) {
            assertThrows(IOException::class.java) { repository.parseRelease(body, "1.0.0") }
        }
    }

    @Test fun cancellationCancelsTheHttpCall() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) { started.complete(Unit) }
            override fun canceled(call: okhttp3.Call) { cancelled.complete(Unit) }
        }).build()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(release()).headersDelay(1, TimeUnit.SECONDS).build())
            val repository = UpdateRepository(client, server.url("/latest").toString())
            val job = launch { repository.check("1.0.0") }
            withTimeout(5000) { started.await() }
            job.cancelAndJoin()
            withTimeout(5000) { cancelled.await() }
        }
    }

    @Test fun httpSuccessErrorsAndTimeout() {
        MockWebServer().use { server ->
            server.start()
            val repository = UpdateRepository(OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build(), server.url("/latest").toString())
            server.enqueue(MockResponse.Builder().body(release()).build())
            assertNotNull(runBlocking { repository.check("1.0.0") })
            val request = server.takeRequest()
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["Authorization"])
            for (code in listOf(404, 403, 429, 500)) {
                server.enqueue(MockResponse.Builder().code(code).build())
                assertThrows(IOException::class.java) { runBlocking { repository.check("1.0.0") } }
            }
            server.enqueue(MockResponse.Builder().body(release()).bodyDelay(1, TimeUnit.SECONDS).build())
            assertThrows(IOException::class.java) { runBlocking { repository.check("1.0.0") } }
        }
    }
}
