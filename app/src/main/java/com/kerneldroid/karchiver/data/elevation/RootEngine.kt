package com.kerneldroid.karchiver.data.elevation

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface RootStatus {
    data object Unknown : RootStatus
    data object Available : RootStatus
    data object Denied : RootStatus
    data object Unavailable : RootStatus
}

data class ExecResult(val code: Int, val stdout: String)

object RootEngine : ElevatedFS {
    private const val TAG = "RootEngine"
    private const val PROBE_COMMAND = "id -u"
    private const val EXPECTED_ROOT_UID = "0"
    private const val PROBE_TTL_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 15_000L
    private const val HANDSHAKE_TIMEOUT_MS = 5_000L
    private const val KILL_GRACE_MS = 2_000L
    private const val STREAM_JOIN_MS = 5_000L
    private const val MAX_OUTPUT_CHARS = 2_000_000
    private const val MAX_ERROR_CHARS = 8_192
    private const val MISSING_CODE = 127
    private const val FAILED_CODE = 125
    private const val MARK_PREFIX = "__KARCHIVER_MARK_"
    private const val HELLO_PREFIX = "__KARCHIVER_HELLO_"
    private const val SHELL_DEAD = "\u0000__KARCHIVER_SHELL_DEAD__"

    private val probeLock = Mutex()
    private val shellLock = Mutex()
    private val _status = MutableStateFlow<RootStatus>(RootStatus.Unknown)
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    private var cachedAvailable: Boolean? = null
    private var lastProbeMs: Long = 0L

    @kotlin.jvm.Volatile
    private var mountMasterRejected = false
    val degradedNamespace: Boolean
        get() = mountMasterRejected

    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var shellLines: LinkedBlockingQueue<String>? = null

    private sealed interface ExecOutcome {
        data class Done(val exitCode: Int, val stdout: String) : ExecOutcome
        data object Missing : ExecOutcome
        data object Failed : ExecOutcome
    }

    private enum class SpawnResult {
        Ready,
        Missing,
        Failed,
    }

    private data class OneShotRaw(
        val code: Int,
        val stdout: String,
        val stderr: String,
        val missing: Boolean,
        val failed: Boolean,
    )

    suspend fun refresh() {
        probe()
    }

    suspend fun probe(): Boolean {
        return runProbe(force = true)
    }

    suspend fun execOneShot(script: String, timeoutMs: Long = 60000): ExecResult {
        return withContext(Dispatchers.IO) {
            try {
                if (isFreshNegative()) {
                    val absent = _status.value is RootStatus.Unavailable
                    return@withContext ExecResult(if (absent) MISSING_CODE else FAILED_CODE, "")
                }
                val useMaster = !mountMasterRejected
                val first = runOneShotInternal(
                    if (useMaster) listOf("su", "--mount-master", "-c", script) else listOf("su", "-c", script),
                    timeoutMs,
                )
                if (first.missing) {
                    noteMissing()
                    return@withContext ExecResult(MISSING_CODE, first.stdout)
                }
                if (useMaster && first.code != 0 && isUnsupportedFlag(first.stdout, first.stderr)) {
                    mountMasterRejected = true
                    val second = runOneShotInternal(listOf("su", "-c", script), timeoutMs)
                    if (second.missing) {
                        noteMissing()
                        return@withContext ExecResult(MISSING_CODE, second.stdout)
                    }
                    if (second.code == 0) {
                        noteSuccess()
                    } else {
                        invalidate()
                    }
                    return@withContext ExecResult(second.code, second.stdout)
                }
                if (first.code == 0) {
                    noteSuccess()
                } else {
                    invalidate()
                }
                ExecResult(first.code, first.stdout)
            } catch (_: Exception) {
                Log.e(TAG, "execOneShot failed")
                invalidate()
                ExecResult(FAILED_CODE, "")
            }
        }
    }

    override suspend fun listFiles(dir: File): List<File>? {
        return try {
            if (isFreshNegative()) return null
            when (val outcome = runInteractive("ls -A1 -p -- ${shellQuote(dir.absolutePath)}")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        null
                    } else {
                        noteSuccess()
                        parseLsOutput(dir, outcome.stdout)
                    }
                }
                is ExecOutcome.Missing -> {
                    noteMissing()
                    null
                }
                is ExecOutcome.Failed -> {
                    invalidate()
                    null
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "listFiles failed")
            null
        }
    }

    override suspend fun deleteRecursively(targets: List<File>): Boolean {
        if (targets.isEmpty()) return true
        return try {
            val quoted = targets.joinToString(" ") { shellQuote(it.absolutePath) }
            execOneShot("rm -rf -- $quoted").code == 0
        } catch (_: Exception) {
            Log.e(TAG, "deleteRecursively failed")
            false
        }
    }

    override suspend fun mkdirs(dir: File): Boolean {
        return try {
            if (isFreshNegative()) return false
            when (val outcome = runInteractive("mkdir -p -- ${shellQuote(dir.absolutePath)}")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        false
                    } else {
                        noteSuccess()
                        true
                    }
                }
                is ExecOutcome.Missing -> {
                    noteMissing()
                    false
                }
                is ExecOutcome.Failed -> {
                    invalidate()
                    false
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "mkdirs failed")
            false
        }
    }

    override suspend fun copyInto(src: File, dst: File): Boolean {
        return try {
            if (isFreshNegative()) return false
            val result = execOneShot("cp -- ${shellQuote(src.absolutePath)} ${shellQuote(dst.absolutePath)}")
            if (result.code != 0) {
                invalidate()
                false
            } else {
                noteSuccess()
                true
            }
        } catch (_: Exception) {
            invalidate()
            false
        }
    }

    override suspend fun chmod(path: File, mode: Int): Boolean {        if (mode < 0) return false
        return try {
            if (isFreshNegative()) return false
            val octal = String.format(Locale.US, "%o", mode)
            when (val outcome = runInteractive("chmod $octal -- ${shellQuote(path.absolutePath)}")) {
                is ExecOutcome.Done -> {
                    if (outcome.exitCode != 0) {
                        invalidate()
                        false
                    } else {
                        noteSuccess()
                        true
                    }
                }
                is ExecOutcome.Missing -> {
                    noteMissing()
                    false
                }
                is ExecOutcome.Failed -> {
                    invalidate()
                    false
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "chmod failed")
            false
        }
    }

    private suspend fun isFreshNegative(): Boolean {
        probeLock.withLock {
            val cached = cachedAvailable
            if (cached == null) return false
            if (SystemClock.elapsedRealtime() - lastProbeMs >= PROBE_TTL_MS) return false
            return !cached
        }
    }

    private suspend fun noteSuccess() {
        probeLock.withLock {
            cachedAvailable = true
            lastProbeMs = SystemClock.elapsedRealtime()
            _status.value = RootStatus.Available
        }
    }

    private suspend fun noteMissing() {
        probeLock.withLock {
            cachedAvailable = false
            lastProbeMs = SystemClock.elapsedRealtime()
            _status.value = RootStatus.Unavailable
        }
    }

    private suspend fun invalidate() {
        probeLock.withLock {
            cachedAvailable = null
            lastProbeMs = 0L
        }
    }

    private suspend fun runProbe(force: Boolean): Boolean {
        probeLock.withLock {
            if (!force) {
                val cached = cachedAvailable
                if (cached != null && SystemClock.elapsedRealtime() - lastProbeMs < PROBE_TTL_MS) {
                    return cached
                }
            }
            val probed = probeRootUnderLock()
            cachedAvailable = probed == RootStatus.Available
            lastProbeMs = SystemClock.elapsedRealtime()
            _status.value = probed
            return probed == RootStatus.Available
        }
    }

    private suspend fun probeRootUnderLock(): RootStatus {
        return try {
            when (val outcome = runInteractive(PROBE_COMMAND)) {
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

    private suspend fun runInteractive(script: String): ExecOutcome {
        return withContext(Dispatchers.IO) {
            shellLock.withLock {
                try {
                    when (ensureShellLocked()) {
                        SpawnResult.Missing -> ExecOutcome.Missing
                        SpawnResult.Failed -> ExecOutcome.Failed
                        SpawnResult.Ready -> sendLocked(script)
                    }
                } catch (_: Exception) {
                    destroyShellLocked()
                    ExecOutcome.Failed
                }
            }
        }
    }

    private fun ensureShellLocked(): SpawnResult {
        try {
            val proc = shellProcess
            val writer = shellWriter
            val queue = shellLines
            if (proc != null && writer != null && queue != null) {
                val alive = try {
                    proc.isAlive
                } catch (_: Exception) {
                    false
                }
                if (alive) return SpawnResult.Ready
                destroyShellLocked()
            } else {
                destroyShellLocked()
            }
            if (!mountMasterRejected) {
                val started = tryStartShell(listOf("su", "--mount-master"))
                if (started == SpawnResult.Ready) return SpawnResult.Ready
                if (started == SpawnResult.Missing) return SpawnResult.Missing
                mountMasterRejected = true
            }
            return tryStartShell(listOf("su"))
        } catch (_: Exception) {
            return SpawnResult.Failed
        }
    }

    private fun tryStartShell(args: List<String>): SpawnResult {
        destroyShellLocked()
        val process: Process
        try {
            process = ProcessBuilder(args).redirectErrorStream(false).start()
        } catch (_: IOException) {
            return SpawnResult.Missing
        } catch (_: SecurityException) {
            return SpawnResult.Missing
        } catch (_: Exception) {
            return SpawnResult.Failed
        }
        val queue = LinkedBlockingQueue<String>()
        val readerThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = try {
                            reader.readLine()
                        } catch (_: Exception) {
                            break
                        }
                        if (line == null) break
                        queue.offer(line)
                    }
                }
            } catch (_: Exception) {
            } finally {
                queue.offer(SHELL_DEAD)
            }
        }
        readerThread.isDaemon = true
        val stderrThread = Thread {
            drainCapped(process.errorStream, MAX_ERROR_CHARS)
        }
        stderrThread.isDaemon = true
        readerThread.start()
        stderrThread.start()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        shellProcess = process
        shellWriter = writer
        shellLines = queue
        val token = HELLO_PREFIX + UUID.randomUUID().toString().replace("-", "")
        try {
            writer.write("echo $token")
            writer.newLine()
            writer.flush()
        } catch (_: Exception) {
            destroyShellLocked()
            return SpawnResult.Failed
        }
        val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
        try {
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    destroyShellLocked()
                    return SpawnResult.Failed
                }
                val line = queue.poll(remaining, TimeUnit.MILLISECONDS)
                if (line == null) {
                    destroyShellLocked()
                    return SpawnResult.Failed
                }
                if (line == SHELL_DEAD) {
                    destroyShellLocked()
                    return SpawnResult.Failed
                }
                if (line.trim() == token) return SpawnResult.Ready
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            destroyShellLocked()
            return SpawnResult.Failed
        } catch (_: Exception) {
            destroyShellLocked()
            return SpawnResult.Failed
        }
    }

    private fun sendLocked(script: String): ExecOutcome {
        val writer = shellWriter ?: return ExecOutcome.Failed
        val queue = shellLines ?: return ExecOutcome.Failed
        val process = shellProcess
        try {
            if (process != null && !process.isAlive) {
                destroyShellLocked()
                return ExecOutcome.Failed
            }
        } catch (_: Exception) {
            destroyShellLocked()
            return ExecOutcome.Failed
        }
        val mark = MARK_PREFIX + UUID.randomUUID().toString().replace("-", "")
        try {
            writer.write("( $script ) 2>&1")
            writer.newLine()
            writer.write("echo $mark \$?")
            writer.newLine()
            writer.flush()
        } catch (_: Exception) {
            destroyShellLocked()
            return ExecOutcome.Failed
        }
        val output = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + CALL_TIMEOUT_MS
        try {
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    destroyShellLocked()
                    return ExecOutcome.Failed
                }
                val line = queue.poll(remaining, TimeUnit.MILLISECONDS)
                if (line == null) {
                    destroyShellLocked()
                    return ExecOutcome.Failed
                }
                if (line == SHELL_DEAD || line.contains(SHELL_DEAD)) {
                    destroyShellLocked()
                    return ExecOutcome.Failed
                }
                if (line.startsWith(mark)) {
                    val code = line.substringAfterLast(" ").trim().toIntOrNull() ?: 1
                    return ExecOutcome.Done(code, output.toString())
                }
                if (output.length < MAX_OUTPUT_CHARS) {
                    val room = MAX_OUTPUT_CHARS - output.length
                    if (line.length <= room) {
                        output.append(line).append('\n')
                    } else {
                        output.append(line, 0, room)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            destroyShellLocked()
            return ExecOutcome.Failed
        } catch (_: Exception) {
            destroyShellLocked()
            return ExecOutcome.Failed
        }
    }

    private fun destroyShellLocked() {
        val writer = shellWriter
        val process = shellProcess
        shellWriter = null
        shellLines = null
        shellProcess = null
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        if (process == null) return
        try {
            try {
                process.outputStream.close()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        try {
            if (!process.isAlive) return
            process.destroy()
            try {
                if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun runOneShotInternal(args: List<String>, timeoutMs: Long): OneShotRaw {
        var process: Process? = null
        try {
            try {
                process = ProcessBuilder(args).redirectErrorStream(false).start()
            } catch (_: IOException) {
                return OneShotRaw(MISSING_CODE, "", "", true, false)
            } catch (_: SecurityException) {
                return OneShotRaw(MISSING_CODE, "", "", true, false)
            }
            val active = process ?: return OneShotRaw(MISSING_CODE, "", "", true, false)
            val outputDrain = startDrain(active.inputStream, MAX_OUTPUT_CHARS)
            val errorDrain = startDrain(active.errorStream, MAX_ERROR_CHARS)
            val finished = try {
                active.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (_: Exception) {
                false
            }
            if (!finished) {
                destroyOneShot(active)
                outputDrain.join(STREAM_JOIN_MS)
                errorDrain.join(STREAM_JOIN_MS)
                return OneShotRaw(FAILED_CODE, outputDrain.content(), errorDrain.content(), false, true)
            }
            outputDrain.join(STREAM_JOIN_MS)
            errorDrain.join(STREAM_JOIN_MS)
            val code = try {
                active.exitValue()
            } catch (_: Exception) {
                FAILED_CODE
            }
            return OneShotRaw(code, outputDrain.content(), errorDrain.content(), false, false)
        } catch (_: Exception) {
            return OneShotRaw(FAILED_CODE, "", "", false, true)
        } finally {
            destroyOneShot(process)
        }
    }

    private fun isUnsupportedFlag(stdout: String, stderr: String): Boolean {
        val combined = (stdout + "\n" + stderr).lowercase(Locale.US)
        if (combined.contains("--mount-master")) return true
        if (combined.contains("mount-master")) return true
        if (combined.contains("unrecognized option")) return true
        if (combined.contains("unknown option")) return true
        if (combined.contains("invalid option")) return true
        return false
    }

    private fun startDrain(stream: InputStream, cap: Int): DrainHandle {
        val handle = DrainHandle(stream, cap)
        handle.thread.isDaemon = true
        handle.thread.start()
        return handle
    }

    private fun drainCapped(stream: InputStream, cap: Int) {
        var kept = 0
        val buffer = ByteArray(4096)
        try {
            stream.use { input ->
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (_: Exception) {
                        break
                    }
                    if (read < 0) break
                    kept += read
                    if (kept >= cap + 4096) {
                        try {
                            while (input.read(buffer) >= 0) {
                            }
                        } catch (_: Exception) {
                        }
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun destroyOneShot(process: Process?) {
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
