package com.yafm.filemanager.data.transfer

/** What a running transfer shows. [bytesPerSecond] is 0 until it is known. */
data class TransferProgress(
    val name: String = "",
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val bytesPerSecond: Long = 0,
    val reconnecting: Boolean = false
) {
    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    val secondsLeft: Long? get() = if (bytesPerSecond > 0) (bytesTotal - bytesDone).coerceAtLeast(0) / bytesPerSecond else null
}

/** Sums up a transfer and reports it a few times a second. */
class TransferMeter(
    filesTotal: Int,
    bytesTotal: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val report: (TransferProgress) -> Unit
) {
    private var state = TransferProgress(filesTotal = filesTotal, bytesTotal = bytesTotal)
    private var fileBytes = 0L
    private var windowStart = clock()
    private var windowBytes = 0L
    private var lastReport = 0L

    @Synchronized
    fun startFile(name: String) {
        fileBytes = 0
        state = state.copy(name = name)
        publish(force = true)
    }

    /** The file goes on from [offset], after a retry or a partial file. Bytes counted for it before are replaced. */
    @Synchronized
    fun fileAt(offset: Long) {
        state = state.copy(bytesDone = state.bytesDone - fileBytes + offset)
        fileBytes = offset
        windowStart = clock()
        windowBytes = 0
        publish(force = true)
    }

    @Synchronized
    fun add(bytes: Int) {
        fileBytes += bytes
        windowBytes += bytes
        state = state.copy(bytesDone = state.bytesDone + bytes)
        val now = clock()
        if (now - windowStart >= SPEED_WINDOW_MS) {
            val speed = windowBytes * 1000 / (now - windowStart)
            // Smoothed: a single slow moment must not make the time left jump.
            state = state.copy(bytesPerSecond = if (state.bytesPerSecond == 0L) speed else (state.bytesPerSecond * 3 + speed) / 4)
            windowStart = now
            windowBytes = 0
        }
        publish()
    }

    @Synchronized
    fun finishFile() {
        fileBytes = 0
        state = state.copy(filesDone = state.filesDone + 1)
        publish(force = true)
    }

    /** Bytes that need no copying: skipped, or moved inside the storage. */
    @Synchronized
    fun skip(bytes: Long) {
        state = state.copy(bytesDone = state.bytesDone + bytes, filesDone = state.filesDone + 1)
        publish(force = true)
    }

    @Synchronized
    fun reconnecting(value: Boolean) {
        if (state.reconnecting == value) return
        state = state.copy(reconnecting = value)
        publish(force = true)
    }

    private fun publish(force: Boolean = false) {
        val now = clock()
        if (!force && now - lastReport < REPORT_MS) return
        lastReport = now
        report(state)
    }

    private companion object {
        const val REPORT_MS = 250L
        const val SPEED_WINDOW_MS = 1000L
    }
}
