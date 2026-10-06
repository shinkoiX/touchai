package app.touchai.core.openai

import io.ktor.http.Url
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Listens only on the device's IPv4 loopback, on a fresh available port. */
class ChatGptCallbackServer : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    val redirectUri: String = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun awaitCode(oauth: ChatGptOAuth, attempt: ChatGptAuthorization): ChatGptAuthorizationCode = withTimeout(300_000) {
        withContext(Dispatchers.IO) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    socket.soTimeout = 1_000
                    val line = StringBuilder()
                    val readDeadline = System.nanoTime() + 2_000_000_000L
                    try {
                        val input = socket.getInputStream()
                        while (line.length < 16_384 && System.nanoTime() < readDeadline) {
                            currentCoroutineContext().ensureActive()
                            val byte = input.read()
                            if (byte == -1 || byte == 10) break
                            line.append(byte.toChar())
                        }
                    } catch (_: SocketTimeoutException) { return@use }
                    val request = line.toString().trimEnd('\r').split(' ')
                    val target = request.getOrNull(1)
                    val callback = target?.takeIf { it.startsWith("/auth/callback?") }?.let {
                        runCatching { Url(redirectUri.substringBefore("/auth/callback") + it) }.getOrNull()
                    }
                    val matched = request.size == 3 && request[0] == "GET" && line.length < 16_384 && System.nanoTime() < readDeadline &&
                        callback?.parameters?.getAll("state") == listOf(attempt.state)
                    val result = if (matched) runCatching { oauth.authorizationCode(attempt, callback.toString()) } else null
                    val accepted = result?.isSuccess == true
                    val body = if (accepted) "Authorization received. Return to TouchAI." else "This sign-in callback was not accepted. Return to TouchAI."
                    val status = if (accepted) "200 OK" else "400 Bad Request"
                    // The browser may close its tab after delivering the code; it still remains usable.
                    runCatching { socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nCache-Control: no-store\r\n" +
                        "Referrer-Policy: no-referrer\r\nConnection: close\r\n\r\n$body").toByteArray()) }
                    if (result != null) return@withContext result.getOrThrow()
                }
            }
            @Suppress("UNREACHABLE_CODE") error("Callback listener stopped.")
        }
    }

    override fun close() = server.close()
}
