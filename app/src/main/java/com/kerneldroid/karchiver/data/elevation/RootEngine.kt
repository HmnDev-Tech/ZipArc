package com.kerneldroid.karchiver.data.elevation

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RootStatus {
    data object Unknown : RootStatus
    data object Available : RootStatus
    data object Denied : RootStatus
    data object Unavailable : RootStatus
}

object RootEngine : ElevatedFS {
    private const val TAG = "RootEngine"
    private const val PROBE_COMMAND = "id -u"
    private const val EXPECTED_ROOT_UID = "0"
    private const val PROBE_TTL_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 15_000L
    private const val KILL_GRACE_MS = 2_000L
    private const val STREAM_JOIN_MS = 5_000L
    private const val MAX_OUTPUT_CHARS = 2_000_000
    private const val MAX_ERROR_CHARS = 8_192

    private val probeLock = Mutex()
    private val _status = MutableStateFlow<RootStatus>(RootStatus.Unknown)
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    private var cachedAvailable: Boolean? = null
    private var lastProbeMs: Long = 0L

    private sealed interface ExecOutcome {
        data class Done(val exitCode: Int, val stdout: String) : ExecOutcome
        data object Missing : ExecOutcome
        data object Failed : ExecOutcome
    }

    suspend fun refresh() {
        runProbe(force = true)
    }

    override suspend fun listFiles(dir: File): List<File>? {
        if (!ensureAvailable()) return null
        return try {
            when (val outcome = runSuCommand("ls -A1 -p -- ${shellQuote(dir.absolutePath)}")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        null
                    } else {
                        parseLsOutput(dir, outcome.stdout)
                    }
                }
                else -> {
                    markNegative(outcome)
                    null
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "listFiles failed")
            invalidate()
            null
        }
    }

    override suspend fun deleteRecursively(targets: List<File>): Boolean {
        if (targets.isEmpty()) return true
        if (!ensureAvailable()) return false
        return try {
            val quoted = targets.joinToString(" ") { shellQuote(it.absolutePath) }
            when (val outcome = runSuCommand("rm -rf -- $quoted")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        false
                    } else {
                        true
                    }
                }
                else -> {
                    markNegative(outcome)
                    false
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "deleteRecursively failed")
            invalidate()
            false
        }
    }

    override suspend fun mkdirs(dir: File): Boolean {
        if (!ensureAvailable()) return false
        return try {
            when (val outcome = runSuCommand("mkdir -p -- ${shellQuote(dir.absolutePath)}")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        false
                    } else {
                        true
                    }
                }
                else -> {
                    markNegative(outcome)
                    false
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "mkdirs failed")
            invalidate()
            false
        }
    }

    override suspend fun chmod(path: File, mode: Int): Boolean {
        if (mode < 0) return false
        if (!ensureAvailable()) return false
        return try {
            val octal = String.format(Locale.US, "%o", mode)
            when (val outcome = runSuCommand("chmod $octal -- ${shellQuote(path.absolutePath)}")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        false
                    } else {
                        true
                    }
                }
                else -> {
                    markNegative(outcome)
                    false
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "chmod failed")
            invalidate()
            false
        }
    }

    private suspend fun ensureAvailable(): Boolean {
        probeLock.withLock {
            val cached = cachedAvailable
            if (cached != null && SystemClock.elapsedRealtime() - lastProbeMs < PROBE_TTL_MS) {
                return cached
            }
        }
        return runProbe(force = false)
    }

    private suspend fun runProbe(force: Boolean): Boolean {
        probeLock.withLock {
            if (!force) {
                val cached = cachedAvailable
                if (cached != null && SystemClock.elapsedRealtime() - lastProbeMs < PROBE_TTL_MS) {
                    return cached
                }
            }
            val probed = probeRoot()
            cachedAvailable = probed == RootStatus.Available
            lastProbeMs = SystemClock.elapsedRealtime()
            _status.value = probed
            return probed == RootStatus.Available
        }
    }

    private suspend fun probeRoot(): RootStatus {
        return try {
            when (val outcome = runSuCommand(PROBE_COMMAND)) {
                is ExecOutcome.Done ->
                    if (outcome.exitCode == 0 && outcome.stdout.trim() == EXPECTED_ROOT_UID) {
                        RootStatus.Available
                    } else {
                        RootStatus.Denied
                    }
                is ExecOutcome.Missing -> RootStatus.Unavailable
                is ExecOutcome.Failed -> RootStatus.Denied
            }
        } catch (_: Exception) {
            Log.e(TAG, "root probe failed")
            RootStatus.Denied
        }
    }

    private suspend fun markNegative(outcome: ExecOutcome) {
        val negative = if (outcome is ExecOutcome.Missing) RootStatus.Unavailable else RootStatus.Denied
        probeLock.withLock {
            cachedAvailable = false
            lastProbeMs = SystemClock.elapsedRealtime()
            _status.value = negative
        }
    }

    private suspend fun invalidate() {
        probeLock.withLock {
            cachedAvailable = null
            lastProbeMs = 0L
        }
    }

    private suspend fun runSuCommand(command: String): ExecOutcome {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(CALL_TIMEOUT_MS + KILL_GRACE_MS + STREAM_JOIN_MS) {
                executeSu(command)
            } ?: ExecOutcome.Failed
        }
    }

    private fun executeSu(command: String): ExecOutcome {
        var process: Process? = null
        try {
            process = try {
                ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start()
            } catch (_: IOException) {
                return ExecOutcome.Missing
            } catch (_: SecurityException) {
                return ExecOutcome.Missing
            }
            val active = process
            val errorDrain = startDrain(active.errorStream, MAX_ERROR_CHARS)
            val outputDrain = startDrain(active.inputStream, MAX_OUTPUT_CHARS)
            val finished = try {
                active.waitFor(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished) {
                destroySu(active)
                outputDrain.join(STREAM_JOIN_MS)
                errorDrain.join(STREAM_JOIN_MS)
                return ExecOutcome.Failed
            }
            outputDrain.join(STREAM_JOIN_MS)
            errorDrain.join(STREAM_JOIN_MS)
            return ExecOutcome.Done(active.exitValue(), outputDrain.content())
        } catch (_: Exception) {
            return ExecOutcome.Failed
        } finally {
            destroySu(process)
        }
    }

    private fun startDrain(stream: InputStream, cap: Int): DrainHandle {
        val handle = DrainHandle(stream, cap)
        handle.thread.isDaemon = true
        handle.thread.start()
        return handle
    }

    private fun destroySu(process: Process?) {
        if (process == null) return
        try {
            if (!process.isAlive) return
            process.destroy()
            if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun parseLsOutput(dir: File, output: String): List<File> {
        if (output.isEmpty()) return emptyList()
        val lines = output.split('\n')
        val names = lines.dropLast(1)
        val result = ArrayList<File>(names.size)
        for (raw in names) {
            if (raw.isEmpty()) continue
            if (raw.contains('\r')) continue
            if (raw == "." || raw == "..") continue
            val name = if (raw.endsWith("/") && raw.length > 1) {
                raw.dropLast(1)
            } else if (raw == "/") {
                continue
            } else {
                raw
            }
            if (name.isEmpty() || name.contains('\r')) continue
            result.add(File(dir, name))
        }
        return result
    }

    private class DrainHandle(
        private val stream: InputStream,
        private val cap: Int,
    ) {
        private val builder = StringBuilder()
        val thread = Thread {
            val buffer = CharArray(4096)
            try {
                stream.bufferedReader().use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(builder) {
                            if (builder.length < cap) {
                                builder.append(buffer, 0, read.coerceAtMost(cap - builder.length))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        fun content(): String {
            synchronized(builder) {
                return builder.toString()
            }
        }

        fun join(timeoutMs: Long) {
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
