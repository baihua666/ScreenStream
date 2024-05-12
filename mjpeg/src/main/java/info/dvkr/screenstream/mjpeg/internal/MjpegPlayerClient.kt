package info.dvkr.screenstream.mjpeg.internal

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.floor

public class MjpegPlayerClient (
    private val eventListener: EventListener,
){
    private var url: String? = null
    private var clientId: String? = null

    private var webSocket: okhttp3.WebSocket? = null

    private val okHttpClient = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        .build()

    public fun start(url: String) {
        this.url = url
        clientId = randomString(16)

        openSocket(url, clientId!!)
    }

    private fun randomString(length: Int): String {
        var result = ""
        val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val charactersLength: Int = characters.length
        for (i in 0 until length) {
            result += characters[floor(Math.random() * charactersLength).toInt()]
        }
        return result
    }

    private fun send(type: String, data: Any?) {
        webSocket?.send(JSONObject().put("type", type).apply { if (data != null) put("data", data) }.toString())
    }

    @Throws(IllegalArgumentException::class)
    internal fun openSocket(url: String, clientId: String) {
        XLog.d(getLog("openSocket"))

        val fullUrl = "$url/socket?clientId=$clientId"

        val request: Request = Request.Builder().url(fullUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                eventListener.onSocketConnected()
                send("CONNECT", null)
            }

            /** Invoked when a text (type `0x1`) message has been received. */
            @Suppress("UNNECESSARY_SAFE_CALL")
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                XLog.d("onMessage")
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return

                when (val type = msg.optString("type").uppercase()) {
                    "HEARTBEAT" -> XLog.d("HEARTBEAT")

                    "UNAUTHORIZED" -> when {
                        msg.optString("data") == "ADDRESS_BLOCKED" -> XLog.d("ADDRESS_BLOCKED")
                        msg.optString("data") == "WRONG_PIN" -> XLog.d("WRONG_PIN")
                        else -> XLog.d("UNAUTHORIZED:" + msg.optString("data"))
                    }

                    "RELOAD" ->  {
                        XLog.d("RELOAD")
                    }

                    "STREAM_ADDRESS" ->  {
                        XLog.d("STREAM_ADDRESS")
                        msg.getJSONObject("data").optString("streamAddress")?.let {
                            eventListener.onStreamAddress("$url/$it?clientId=$clientId".replace("ws://", "http://"))
                        }
                    }

                    else -> {
                        val m = "Unknown message type: $type"
                        XLog.e(this@MjpegPlayerClient.getLog("socket", m), IllegalArgumentException(m))
                    }
                }
            }

            /** Invoked when a binary (type `0x2`) message has been received. */
            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: ByteString) {
                XLog.d("onMessage")
            }

            /**
             * Invoked when the remote peer has indicated that no more incoming messages will be transmitted.
             */
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                XLog.d("onClosing")
            }

            /**
             * Invoked when both peers have indicated that no more messages will be transmitted and the
             * connection has been successfully released. No further calls to this listener will be made.
             */
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                XLog.d("onClosed")
                eventListener.onSocketDisconnected(reason)
            }

            /**
             * Invoked when a web socket has been closed due to an error reading from or writing to the
             * network. Both outgoing and incoming messages may have been lost. No further calls to this
             * listener will be made.
             */
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                XLog.e(this@MjpegPlayerClient.getLog("SOCKET_ERROR", t.message))
                val message = t.message ?: ""
                eventListener.onError(message)
            }
        })
    }


    public interface EventListener {
        public fun onSocketConnected()
        public fun onTokenExpired()
        public fun onSocketDisconnected(reason: String)
        public fun onStreamAddress(address: String)

        public fun onError(cause: String)
    }


}