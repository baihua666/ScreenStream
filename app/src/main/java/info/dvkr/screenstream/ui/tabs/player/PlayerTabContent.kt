package info.dvkr.screenstream.ui.tabs.player

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.longdo.mjpegviewer.MjpegView
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.module.PlayerModule
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun PlayerTabContent(
    modifier: Modifier = Modifier,
    playerModule: PlayerModule = koinInject(),
    playerViewModel: PlayerTabViewModel = koinViewModel()
) {
    val defaultUrl by rememberSaveable { mutableStateOf("ws://192.168.3.100:8080") }
    var inputText by rememberSaveable { mutableStateOf(playerModule.serverUrl ?: defaultUrl) }

    val focusManager = LocalFocusManager.current

    Column(modifier = modifier.clickable {
        focusManager.clearFocus()
    }.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TextField(
            placeholder = { Text(text = stringResource(id = R.string.app_player_url_hint)) },
            value = inputText,
            onValueChange = {
                inputText = it
                playerModule.serverUrl = it
                            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        TextButton(
            onClick = {
                Log.d("PlayerTabContent", "Show help")
                if (inputText.isBlank()) {
                    return@TextButton
                }
                playerModule.serverUrl = inputText
                playerViewModel.startPlayer(playerModule.serverUrl!!)
            }
        ) {
            Text(text = stringResource(id = android.R.string.ok))
        }

        PlayerView(streamAddressFlow = playerViewModel.streamAddressFlow)
    }
}

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
private fun PlayerView(
    streamAddressFlow: StateFlow<String>,
) {
    val streamAddress: String by streamAddressFlow.collectAsState()
    var isPlaying by rememberSaveable { mutableStateOf(false) }

    var mjpegView by remember { mutableStateOf<MjpegView?>(null) }

    LaunchedEffect(streamAddress) {
        Log.d("PlayerView", "streamAddressFlow: $streamAddress")
        if (streamAddress.isBlank()) {
            if (isPlaying) {
                isPlaying = false
                mjpegView?.stopStream()
            }
        }
        else {
            mjpegView?.setUrl(streamAddress)
            isPlaying = true
            mjpegView?.startStream()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        factory = { context ->
            MjpegView(context).apply {
                mjpegView = this
                mode = MjpegView.MODE_FIT_WIDTH
                isAdjustHeight = true
                supportPinchZoomAndPan = true
            }
        },
    )
}


