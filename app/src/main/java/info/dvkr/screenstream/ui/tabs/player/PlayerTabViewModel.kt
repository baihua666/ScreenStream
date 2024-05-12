package info.dvkr.screenstream.ui.tabs.player

import androidx.lifecycle.ViewModel
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.MjpegPlayerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
internal class PlayerTabViewModel : ViewModel() {

    private var playerClient: MjpegPlayerClient? = null

    private val _streamAddressFlow: MutableStateFlow<String> = MutableStateFlow("")
    internal val streamAddressFlow: StateFlow<String> = _streamAddressFlow.asStateFlow()


    init {
        XLog.d(getLog("init"))
    }

    fun startPlayer(url: String) {
        XLog.d(getLog("startPlayer", "address: $url"))
        playerClient = MjpegPlayerClient(ssEventListener)
        playerClient?.start(url)
    }

    private val ssEventListener = object : MjpegPlayerClient.EventListener {
        override fun onSocketConnected() {
            XLog.v(this@PlayerTabViewModel.getLog("SocketSignaling.onSocketConnected"))
//            sendEvent(InternalEvent.StreamCreate)
        }

        override fun onTokenExpired() {
            XLog.v(this@PlayerTabViewModel.getLog("SocketSignaling.onTokenExpired"))
//            sendEvent(InternalEvent.GetNonce(0, true))
        }

        override fun onSocketDisconnected(reason: String) {
            XLog.v(this@PlayerTabViewModel.getLog("SocketSignaling.onSocketDisconnected", reason))
//            sendEvent(InternalEvent.GetNonce(0, false))
        }

        override fun onStreamAddress(address: String) {
            XLog.v(this@PlayerTabViewModel.getLog("SocketSignaling.onStreamAddress", address))
            _streamAddressFlow.value = address
        }

        override fun onError(cause: String) {
            XLog.e(this@PlayerTabViewModel.getLog("SocketSignaling.onError", cause), cause)
        }
    }
}