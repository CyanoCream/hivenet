package id.kampungnet.shared.time

interface Clock {
    fun nowMillis(): Long
}

expect object SystemClock : Clock
