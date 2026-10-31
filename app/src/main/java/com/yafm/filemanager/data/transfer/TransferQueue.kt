package com.yafm.filemanager.data.transfer

import com.yafm.filemanager.R
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.Location
import com.yafm.filemanager.data.repository.FileRepository
import com.yafm.filemanager.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TransferRequest(val items: List<FileItem>, val dest: Location, val move: Boolean, val onConflict: OnConflict)

/** A transfer that stopped with an error; [retry] goes on from where it stopped. */
data class FailedTransfer(val message: String, val retry: TransferRequest)

data class TransferState(
    val current: TransferProgress? = null,
    val queued: Int = 0,
    val failed: FailedTransfer? = null
)

/**
 * Transfers run one after another here and not in a screen's scope: leaving the app does not stop them
 * ([com.yafm.filemanager.data.fs.ConnectionService] keeps it running meanwhile).
 */
class TransferQueue(private val repository: FileRepository, private val describe: (Throwable) -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turn = Mutex()
    private var running: Job? = null

    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    /** Results for the toasts. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Folders whose contents changed. */
    private val _changed = MutableSharedFlow<Location>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<Location> = _changed.asSharedFlow()

    val isActive: Boolean get() = _state.value.let { it.current != null || it.queued > 0 }

    fun enqueue(request: TransferRequest) {
        _state.update { it.copy(queued = it.queued + 1, failed = null) }
        scope.launch {
            turn.withLock {
                _state.update { it.copy(queued = it.queued - 1, current = TransferProgress(filesTotal = request.items.size)) }
                val job = launch { execute(request) }
                running = job
                job.join()
                running = null
                _state.update { it.copy(current = null) }
                _changed.tryEmit(request.dest)
            }
        }
    }

    private suspend fun execute(request: TransferRequest) {
        try {
            val skipped = repository.transfer(request) { p -> _state.update { it.copy(current = p) } }
            val done = request.items.size
            _messages.tryEmit(
                (if (request.move) str(R.string.moved, done) else str(R.string.copied, done)) +
                    if (skipped > 0) str(R.string.links_to_folders_are_not_copied_skipped, skipped) else ""
            )
        } catch (e: CancellationException) {
            _messages.tryEmit(str(R.string.transfer_cancelled))
            throw e
        } catch (e: Exception) {
            // On retry what arrived whole is not sent again, and partial files go on.
            _state.update { it.copy(failed = FailedTransfer(describe(e), request.copy(onConflict = OnConflict.SKIP_SAME))) }
        }
    }

    /** Stops the running transfer; the queued ones go on. */
    fun cancel() {
        running?.cancel()
    }

    fun retry() {
        _state.value.failed?.let { enqueue(it.retry) }
    }

    fun dismissFailed() = _state.update { it.copy(failed = null) }
}
