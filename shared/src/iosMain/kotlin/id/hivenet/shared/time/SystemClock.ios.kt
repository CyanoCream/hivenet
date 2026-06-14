package id.hivenet.shared.time

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
actual object SystemClock : Clock {
    override fun nowMillis(): Long = time(null) * 1000L
}
