package id.hivenet.shared.time

actual object SystemClock : Clock {
    override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
}
