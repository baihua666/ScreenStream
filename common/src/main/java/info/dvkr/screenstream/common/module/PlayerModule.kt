package info.dvkr.screenstream.common.module

import androidx.compose.runtime.Immutable
import org.koin.core.annotation.Single

@Single
@Immutable
public class PlayerModule() {
    public var serverUrl: String? = null
}