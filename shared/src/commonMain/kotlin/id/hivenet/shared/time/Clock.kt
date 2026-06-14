package id.hivenet.shared.time

interface Clock {
    fun nowMillis(): Long
}

expect object SystemClock : Clock
