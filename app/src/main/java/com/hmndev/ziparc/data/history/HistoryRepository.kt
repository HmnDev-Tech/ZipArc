package com.hmndev.ziparc.data.history

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.flow.Flow

class HistoryRepository private constructor(private val db: HistoryDatabase) {

    private val dao = db.historyDao()

    val entries: Flow<List<HistoryEntry>> = dao.observeAll()

    suspend fun record(file: File) {
        val isDirectory = file.isDirectory
        record(
            path = file.absolutePath,
            name = file.name.ifEmpty { file.absolutePath },
            isDirectory = isDirectory,
            extension = if (isDirectory) "" else file.extension.lowercase(),
            size = if (isDirectory) 0L else file.length()
        )
    }

    suspend fun record(
        path: String,
        name: String,
        isDirectory: Boolean,
        extension: String = "",
        size: Long = 0L,
        visitedAt: Long = System.currentTimeMillis()
    ) {
        if (path.isBlank()) return
        db.withTransaction {
            val previous = dao.find(path)
            dao.upsert(
                HistoryEntry(
                    path = path,
                    name = name,
                    isDirectory = isDirectory,
                    extension = extension,
                    size = size,
                    lastVisitedAt = visitedAt,
                    visitCount = (previous?.visitCount ?: 0) + 1
                )
            )
            if (previous == null && dao.count() > MAX_ENTRIES) dao.prune(MAX_ENTRIES)
        }
    }

    suspend fun remove(path: String) = dao.remove(path)

    suspend fun clear() = dao.clear()

    suspend fun count(): Int = dao.count()

    companion object {
        const val MAX_ENTRIES = 1000

        @Volatile
        private var instance: HistoryRepository? = null

        fun get(context: Context): HistoryRepository =
            instance ?: synchronized(this) {
                instance ?: HistoryRepository(HistoryDatabase.get(context)).also { instance = it }
            }
    }
}
