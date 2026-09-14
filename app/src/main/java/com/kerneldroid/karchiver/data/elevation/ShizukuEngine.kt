package com.kerneldroid.karchiver.data.elevation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

object ShizukuEngine : ElevatedFS {

    sealed interface ShizukuStatus {
        data object NoBinder : ShizukuStatus
        data object PermissionRequired : ShizukuStatus
        data object Ready : ShizukuStatus
        data object Unavailable : ShizukuStatus
    }

    private const val TAG = "ShizukuEngine"
    private const val PERMISSION_REQUEST_CODE = 9917
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val USER_SERVICE_TAG = "karchiver-privileged-fs-v1"
    private const val SUCCESS = 0

    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.NoBinder)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindMutex = Mutex()
    private val listenersRegistered = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedService: IPrivilegedFS? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (listenersRegistered.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderReceivedListener {
                    scope.launch { refresh() }
                }
                Shizuku.addBinderDeadListener {
                    cachedService = null
                    _status.value = ShizukuStatus.NoBinder
                }
                Shizuku.addRequestPermissionResultListener { requestCode, _ ->
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        scope.launch { refresh() }
                    }
                }
            } catch (_: Exception) {
                listenersRegistered.set(false)
                Log.e(TAG, "listener registration failed")
                _status.value = ShizukuStatus.Unavailable
            }
        }
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        _status.value = withContext(Dispatchers.IO) { probeStatus() }
    }

    private fun probeStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) return ShizukuStatus.NoBinder
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ShizukuStatus.PermissionRequired
            }
            ShizukuStatus.Ready
        } catch (_: Exception) {
            Log.e(TAG, "status probe failed")
            ShizukuStatus.Unavailable
        }
    }

    fun requestPermission(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        scope.launch(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder()) {
                    _status.value = ShizukuStatus.NoBinder
                    return@launch
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    _status.value = ShizukuStatus.Ready
                    return@launch
                }
                _status.value = ShizukuStatus.PermissionRequired
                if (activity.isFinishing || activity.isDestroyed) return@launch
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (_: Exception) {
                Log.e(TAG, "permission request failed")
                _status.value = ShizukuStatus.Unavailable
            }
        }
    }

    fun shouldShowRationale(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.shouldShowRequestPermissionRationale()
        } catch (_: Exception) {
            false
        }
    }

    fun shizukuUid(): Int? {
        return try {
            if (!Shizuku.pingBinder()) null else Shizuku.getUid()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun listFiles(dir: File): List<File>? = withContext(Dispatchers.IO) {
        try {
            val service = boundService() ?: return@withContext null
            val entries = try {
                service.listDir(dir.absolutePath)
            } catch (_: Exception) {
                Log.e(TAG, "listFiles failed")
                dropService()
                return@withContext null
            } ?: return@withContext null
            entries.mapNotNull { decodeEntry(dir, it) }
        } catch (_: Exception) {
            Log.e(TAG, "listFiles failed")
            null
        }
    }

    override suspend fun deleteRecursively(targets: List<File>): Boolean = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) return@withContext true
        try {
            val service = boundService() ?: return@withContext false
            try {
                service.deleteAll(targets.map { it.absolutePath }) == SUCCESS
            } catch (_: Exception) {
                Log.e(TAG, "deleteRecursively failed")
                dropService()
                false
            }
        } catch (_: Exception) {
            Log.e(TAG, "deleteRecursively failed")
            false
        }
    }

    override suspend fun mkdirs(dir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = boundService() ?: return@withContext false
            try {
                service.makeDirs(dir.absolutePath) == SUCCESS
            } catch (_: Exception) {
                Log.e(TAG, "mkdirs failed")
                dropService()
                false
            }
        } catch (_: Exception) {
            Log.e(TAG, "mkdirs failed")
            false
        }
    }

    override suspend fun chmod(path: File, mode: Int): Boolean = withContext(Dispatchers.IO) {
        if (mode < 0) return@withContext false
        try {
            val service = boundService() ?: return@withContext false
            try {
                service.setMode(path.absolutePath, mode) == SUCCESS
            } catch (_: Exception) {
                Log.e(TAG, "chmod failed")
                dropService()
                false
            }
        } catch (_: Exception) {
            Log.e(TAG, "chmod failed")
            false
        }
    }

    private suspend fun boundService(): IPrivilegedFS? {
        cachedService?.let { cached ->
            if (cached.asBinder().isBinderAlive) return cached
            cachedService = null
        }
        val context = appContext ?: return null
        return bindMutex.withLock {
            cachedService?.let { cached ->
                if (cached.asBinder().isBinderAlive) return@withLock cached
            }
            cachedService = null
            bindLocked(context)
        }
    }

    private suspend fun bindLocked(context: Context): IPrivilegedFS? = withContext(Dispatchers.IO) {
        try {
            if (!Shizuku.pingBinder()) {
                _status.value = ShizukuStatus.NoBinder
                return@withContext null
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                _status.value = ShizukuStatus.PermissionRequired
                return@withContext null
            }
        } catch (_: Exception) {
            Log.e(TAG, "user service bind failed")
            _status.value = ShizukuStatus.Unavailable
            return@withContext null
        }
        val ready = CompletableDeferred<IPrivilegedFS>()
        val component = ComponentName(context, PrivilegedFSService::class.java)
        val args = Shizuku.UserServiceArgs(component).daemon(false).tag(USER_SERVICE_TAG)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null) {
                        ready.complete(IPrivilegedFS.Stub.asInterface(binder))
                    } else {
                        ready.completeExceptionally(IllegalStateException("Privileged service binder missing"))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    cachedService = null
                    scope.launch { refresh() }
                }
            }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (_: Exception) {
            Log.e(TAG, "user service bind failed")
            _status.value = ShizukuStatus.Unavailable
            return@withContext null
        }
        val bound =
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                try {
                    ready.await()
                } catch (_: Exception) {
                    Log.e(TAG, "user service bind failed")
                    null
                }
            }
        if (bound == null) {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (_: Exception) {
                Log.e(TAG, "user service unbind failed")
            }
            return@withContext null
        }
        cachedService = bound
        _status.value = ShizukuStatus.Ready
        bound
    }

    private fun dropService() {
        cachedService = null
    }

    private fun decodeEntry(dir: File, encoded: String): File? {
        val name = encoded.substringBefore('\u0000')
        if (name.isEmpty() || name.contains('/')) return null
        return File(dir, name)
    }
}
