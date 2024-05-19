package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import info.dvkr.screenstream.mjpeg.MjpegModuleService
import kotlinx.parcelize.Parcelize

public open class MjpegEvent(public val priority: Int) {

    internal object Priority {
        internal const val NONE: Int = -1
        internal const val RESTART_IGNORE: Int = 10
        internal const val RECOVER_IGNORE: Int = 20
        internal const val DESTROY_IGNORE: Int = 30
    }

    public sealed class Intentable(priority: Int) : MjpegEvent(priority), Parcelable {
        internal companion object {
            private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"

            @Suppress("DEPRECATION")
            internal fun fromIntent(intent: Intent): Intentable? =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_PARCELABLE)
                else intent.getParcelableExtra(EXTRA_PARCELABLE, Intentable::class.java)
        }

        @Parcelize public data object StartService : Intentable(Priority.NONE)
        @Parcelize public data class StopStream(val reason: String) : Intentable(Priority.RESTART_IGNORE)
        @Parcelize internal data object RecoverError : Intentable(Priority.RECOVER_IGNORE)

        public fun toIntent(context: Context): Intent = MjpegModuleService.getIntent(context).putExtra(EXTRA_PARCELABLE, this)
    }

    public data object CastPermissionsDenied : MjpegEvent(Priority.RECOVER_IGNORE)
    public data class StartProjection(val intent: Intent) : MjpegEvent(Priority.RECOVER_IGNORE)
    internal data object CreateNewPin : MjpegEvent(Priority.DESTROY_IGNORE)
}