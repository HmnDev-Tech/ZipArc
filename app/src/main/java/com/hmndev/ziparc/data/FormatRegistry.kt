package com.hmndev.ziparc.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class FormatCategory { ARCHIVE, APP, IMAGE, AUDIO, VIDEO, SUBTITLE, DOCUMENT, CODE, SYSTEM, UNKNOWN }

data class FormatInfo(
    val extension: String,
    val mime: String,
    val icon: ImageVector,
    val category: FormatCategory
) {
    companion object {
        val DIRECTORY = FormatInfo("", "inode/directory", Icons.Filled.Folder, FormatCategory.UNKNOWN)
    }
}

object FormatRegistry {

    private val map: Map<String, FormatInfo> = buildMap {
        fun a(ext: String, mime: String) = put(ext, FormatInfo(ext, mime, Icons.Filled.Archive, FormatCategory.ARCHIVE))
        a("zip","application/zip"); a("7z","application/x-7z-compressed"); a("rar","application/vnd.rar")
        a("tar","application/x-tar"); a("gz","application/gzip"); a("tgz","application/gzip")
        a("bz2","application/x-bzip2"); a("xz","application/x-xz"); a("zst","application/zstd"); a("lz4","application/x-lz4")

        fun app(ext: String, mime: String) = put(ext, FormatInfo(ext, mime, Icons.Filled.Android, FormatCategory.APP))
        app("apk","application/vnd.android.package-archive"); app("xapk","application/x-xapk"); app("apks","application/x-apks")
        app("apkm","application/x-apkm"); app("aab","application/x-authorware-bin")

        listOf("jpg","jpeg").forEach { put(it, FormatInfo(it,"image/jpeg", Icons.Filled.Image, FormatCategory.IMAGE)) }
        listOf("png" to "image/png","webp" to "image/webp","heic" to "image/heic","heif" to "image/heif","avif" to "image/avif","gif" to "image/gif","svg" to "image/svg+xml","dng" to "image/x-adobe-dng","ico" to "image/x-icon").forEach { (e,m) -> put(e, FormatInfo(e,m, Icons.Filled.Image, FormatCategory.IMAGE)) }

        listOf("mp3" to "audio/mpeg","m4a" to "audio/mp4","aac" to "audio/aac","flac" to "audio/flac","wav" to "audio/wav","ogg" to "audio/ogg","opus" to "audio/opus").forEach { (e,m) -> put(e, FormatInfo(e,m, Icons.Filled.AudioFile, FormatCategory.AUDIO)) }
        listOf("mp4" to "video/mp4","mkv" to "video/x-matroska","webm" to "video/webm","mov" to "video/quicktime","avi" to "video/x-msvideo","ts" to "video/mp2t").forEach { (e,m) -> put(e, FormatInfo(e,m, Icons.Filled.VideoFile, FormatCategory.VIDEO)) }
        listOf("srt","vtt","ass").forEach { put(it, FormatInfo(it,"text/plain", Icons.Filled.Subtitles, FormatCategory.SUBTITLE)) }

        listOf("pdf" to "application/pdf","docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document","xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation","txt" to "text/plain","rtf" to "application/rtf","epub" to "application/epub+zip","fb2" to "application/x-fictionbook+xml","mobi" to "application/x-mobipocket-ebook","djvu" to "image/vnd.djvu","cbr" to "application/x-cbr","cbz" to "application/x-cbz").forEach { (e,m) -> put(e, FormatInfo(e,m, Icons.Filled.Description, FormatCategory.DOCUMENT)) }
        listOf("md" to "text/markdown","json" to "application/json","xml" to "application/xml","yaml" to "text/yaml","yml" to "text/yaml","csv" to "text/csv","log" to "text/plain","html" to "text/html","htm" to "text/html","sh" to "application/x-sh","ini" to "text/plain","conf" to "text/plain").forEach { (e,m) -> put(e, FormatInfo(e,m, Icons.Filled.Code, FormatCategory.CODE)) }
        listOf("vcf" to "text/vcard","ics" to "text/calendar","iso" to "application/x-iso9660-image","img" to "application/x-raw-disk-image","torrent" to "application/x-bittorrent","crt" to "application/x-x509-ca-cert","pem" to "application/x-pem-file").forEach { (e,m) -> put(e, FormatInfo(e,m, Icons.Filled.Settings, FormatCategory.SYSTEM)) }
    }

    fun forExtension(ext: String): FormatInfo =
        map[ext.lowercase().removePrefix(".")] ?: FormatInfo(ext.lowercase(), "application/octet-stream", Icons.AutoMirrored.Filled.InsertDriveFile, FormatCategory.UNKNOWN)

    fun isArchive(ext: String): Boolean = forExtension(ext).category == FormatCategory.ARCHIVE
    fun isApp(ext: String): Boolean = forExtension(ext).category == FormatCategory.APP
}
