package info.dvkr.screenstream.ui.tabs.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.ScreenStreamTab

internal object PlayerTab : ScreenStreamTab {
    override val icon: ImageVector = Icons.Outlined.Stream
    override val iconSelected: ImageVector = Icons.Filled.Stream
    override val labelResId: Int = R.string.app_tab_player

    @Composable
    override fun Content(modifier: Modifier) = PlayerTabContent(modifier)
}