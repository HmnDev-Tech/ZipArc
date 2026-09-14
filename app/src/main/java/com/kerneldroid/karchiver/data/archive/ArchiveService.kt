package com.kerneldroid.karchiver.data.archive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kerneldroid.karchiver.MainActivity
import com.kerneldroid.karchiver.data.CompressFormat
import com.kerneldroid.karchiver.data.FileSystemRepository
import com.kerneldroid.karchiver.data.RustBridge
import com.kerneldroid.karchiver.data.elevation.RootEngine
import com.kerneldroid.karchiver.data.elevation.ShizukuEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ArchiveService : Service() {

    companion object {
        const val ACTION_START = "com.kerneldroid.karchiver.data.archive.ArchiveService.ACTION_START"
        const val ACTION_CANCEL = "com.kerneldroid.karchiver.data.archive.ArchiveService.ACTION_CANCEL"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_SOURCES = "extra_sources"
        const val EXTRA_ARCHIVE = "extra_archive"
        const val EXTRA_DEST = "extra_dest"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_ELEVATION = "extra_elevation"
        const val CHANNEL_ID = "archive_ops"
        const val NOTIFICATION_ID = 1

        fun startCompress(
            context: Context,
            sources: List<File>,
            dest: File,
            format: CompressFormat,
            password: String?,
            elevationMode: String
        ) {
            val intent = Intent(context, ArchiveService::class.java)
            intent.action = ACTION_START
            intent.putExtra(EXTRA_KIND, OpKind.COMPRESS.name)
            intent.putExtra(EXTRA_LABEL, dest.name)
            intent.putExtra(EXTRA_SOURCES, sources.map { it.absolutePath }.toTypedArray())
            intent.putExtra(EXTRA_DEST, dest.absolutePath)
            intent.putExtra(EXTRA_FORMAT, format.name)
            intent.putExtra(EXTRA_PASSWORD, password)
            intent.putExtra(EXTRA_ELEVATION, elevationMode)
            context.startForegroundService(intent)
        }

        fun startExtract(
            context: Context,
            archive: File,
            destDir: File,
            password: String?,
            elevationMode: String
        ) {
            val intent = Intent(context, ArchiveService::class.java)
            intent.action = ACTION_START
            intent.putExtra(EXTRA_KIND, OpKind.EXTRACT.name)
            intent.putExtra(EXTRA_LABEL, archive.name + " -> " + destDir.name)
            intent.putExtra(EXTRA_ARCHIVE, archive.absolutePath)
            intent.putExtra(EXTRA_DEST, destDir.absolutePath)
            intent.putExtra(EXTRA_PASSWORD, password)
            intent.putExtra(EXTRA_ELEVATION, elevationMode)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ArchiveService::class.java)
            intent.action = ACTION_CANCEL
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scopeJob: Job? = null
    private var owned = false
    private var ownedKind: OpKind = OpKind.COMPRESS
    private var ownedLabel: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_CANCEL) {
            cancelCurrent()
            return START_NOT_STICKY
        }
        if (intent == null || intent.action != ACTION_START) {
            return START_NOT_STICKY
        }
        if (scopeJob?.isActive == true) {
            return START_NOT_STICKY
        }
        val kindName = intent.getStringExtra(EXTRA_KIND)
        val label = intent.getStringExtra(EXTRA_LABEL)
        if (kindName.isNullOrEmpty() || label.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val kind = try {
            OpKind.valueOf(kindName)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val elevationMode = intent.getStringExtra(EXTRA_ELEVATION) ?: "off"
        val srcPaths: Array<String>?
        val archiveStr: String?
        val destStr: String?
        val formatName: String?
        when (kind) {
            OpKind.COMPRESS -> {
                srcPaths = intent.getStringArrayExtra(EXTRA_SOURCES)
                destStr = intent.getStringExtra(EXTRA_DEST)
                formatName = intent.getStringExtra(EXTRA_FORMAT)
                archiveStr = null
                if (srcPaths.isNullOrEmpty() || destStr.isNullOrEmpty() || formatName.isNullOrEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            OpKind.EXTRACT -> {
                archiveStr = intent.getStringExtra(EXTRA_ARCHIVE)
                destStr = intent.getStringExtra(EXTRA_DEST)
                srcPaths = null
                formatName = null
                if (archiveStr.isNullOrEmpty() || destStr.isNullOrEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        owned = true
        ownedKind = kind
        ownedLabel = label
        ArchiveOpManager.started(kind, label)
        ensureChannel()
        val initial = buildProgressNotification(label, 0L, 0L)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initial,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val capturedSrcs = srcPaths?.toList()
        val capturedArchive = archiveStr
        val capturedDest = destStr
        val capturedFormat = formatName
        scopeJob = scope.launch {
            val progressJob = launch {
                while (isActive) {
                    delay(250)
                    val current = readProgress()
                    ArchiveOpManager.progress(current.first, current.second)
                    val updated = buildProgressNotification(label, current.first, current.second)
                    try {
                        val manager =
                            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, updated)
                    } catch (_: Exception) {
                    }
                }
            }
            val outcome = executeOp(
                kind,
                capturedSrcs,
                capturedArchive,
                capturedDest,
                capturedFormat,
                password,
                elevationMode
            )
            progressJob.cancel()
            ArchiveOpManager.finished(kind, label, outcome)
            val done = buildCompletionNotification(label, outcome)
            try {
                ServiceCompat.stopForeground(this@ArchiveService, STOP_FOREGROUND_DETACH)
            } catch (_: Exception) {
            }
            try {
                val manager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, done)
            } catch (_: Exception) {
            }
            owned = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun executeOp(
        kind: OpKind,
        srcPaths: List<String>?,
        archiveStr: String?,
        destStr: String?,
        formatName: String?,
        password: String?,
        elevationMode: String
    ): OpOutcome {
        return try {
            val repo = FileSystemRepository()
            repo.tempDir = cacheDir
            val engine = when (elevationMode) {
                "shizuku" -> ShizukuEngine
                "root" -> RootEngine
                else -> null
            }
            val result = when (kind) {
                OpKind.COMPRESS -> {
                    val sources = (srcPaths ?: emptyList()).map { File(it) }
                    val dest = File(destStr ?: "")
                    val format = try {
                        CompressFormat.valueOf(formatName ?: CompressFormat.ZIP.name)
                    } catch (_: Exception) {
                        CompressFormat.ZIP
                    }
                    repo.compress(sources, dest, format, password)
                }
                OpKind.EXTRACT -> {
                    repo.extract(File(archiveStr ?: ""), File(destStr ?: ""), password, engine, elevationMode)
                }
            }
            if (result.isSuccess) {
                OpOutcome.Success
            } else {
                val error = result.exceptionOrNull()
                if (error is CancellationException) {
                    OpOutcome.Cancelled
                } else {
                    mapFailure(error?.message)
                }
            }
        } catch (e: CancellationException) {
            OpOutcome.Cancelled
        } catch (e: Exception) {
            mapFailure(e.message)
        }
    }

    private fun mapFailure(message: String?): OpOutcome {
        val raw = message ?: ""
        if (raw.contains("ancell", ignoreCase = true)) {
            return OpOutcome.Cancelled
        }
        if (raw.contains("code=2")) {
            return OpOutcome.Cancelled
        }
        return OpOutcome.Failed(sanitizeMessage(raw))
    }

    private fun sanitizeMessage(message: String): String {
        var out = message.trim().replace('\n', ' ').replace('\r', ' ')
        while (out.contains("  ")) {
            out = out.replace("  ", " ")
        }
        if (out.isBlank()) {
            out = "Operation failed"
        }
        if (out.length > 200) {
            out = out.substring(0, 200)
        }
        return out
    }

    private fun readProgress(): Pair<Long, Long> {
        try {
            if (!RustBridge.isLoaded()) {
                return Pair(0L, 0L)
            }
            val result = RustBridge.getProgress()
            if (result.size < 2) {
                return Pair(0L, 0L)
            }
            return Pair(result[0], result[1])
        } catch (_: Throwable) {
            return Pair(0L, 0L)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel =
                    NotificationChannel(CHANNEL_ID, "Archive operations", NotificationManager.IMPORTANCE_LOW)
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildProgressNotification(label: String, done: Long, total: Long): Notification {
        val indeterminate = total <= 0L
        val text = if (indeterminate) {
            if (done > 0L) {
                Formatter.formatShortFileSize(this, done) + " processed"
            } else {
                "Working"
            }
        } else {
            Formatter.formatShortFileSize(this, done) + " of " + Formatter.formatShortFileSize(this, total)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            if (total > Int.MAX_VALUE.toLong() || done > Int.MAX_VALUE.toLong()) {
                val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(total.toInt(), done.coerceAtMost(total).toInt(), false)
            }
        }
        val cancelIntent = Intent(this, ArchiveService::class.java)
        cancelIntent.action = ACTION_CANCEL
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val cancelPending = PendingIntent.getService(this, 1, cancelIntent, pendingFlags)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
        return builder.build()
    }

    private fun buildCompletionNotification(label: String, outcome: OpOutcome): Notification {
        val text = when (outcome) {
            is OpOutcome.Success -> "Completed successfully"
            is OpOutcome.Cancelled -> "Cancelled"
            is OpOutcome.Failed -> "Failed: " + outcome.message
        }
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun cancelCurrent() {
        try {
            scopeJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            if (RustBridge.isLoaded()) {
                RustBridge.cancel()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            scopeJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            if (RustBridge.isLoaded()) {
                RustBridge.cancel()
            }
        } catch (_: Exception) {
        }
        if (owned) {
            val current = ArchiveOpManager.active.value
            if (current != null) {
                ArchiveOpManager.finished(current.kind, current.label, OpOutcome.Cancelled)
            }
        }
        owned = false
        super.onDestroy()
    }
}
