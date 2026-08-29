package com.takeback.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/**
 * Helpers for turning a picked [Uri] into something the API can upload.
 *
 * The filename matters more than it used to: the server classifies an
 * attachment by its extension (image / video / audio / file), and hands the
 * name back as the download name. A content:// URI's last path segment is
 * usually an opaque document id, not a filename, so it has to be queried.
 */
object Attachments {

    /** Largest attachment the server will take — mirrors api.MaxUploadBytes. */
    const val MAX_BYTES = 95L * 1024 * 1024

    /**
     * The file's display name, with an extension. Falls back to one derived from
     * the MIME type, and finally to a bare "file" — the server copes with a
     * missing extension, it just can't classify as usefully.
     */
    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) name = c.getString(i)
                    }
            }
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        if (name.isNullOrBlank()) name = "file"

        // No extension? Recover one from the MIME type, so a picked camera video
        // still arrives as a video rather than an unclassifiable blob.
        if (!name!!.contains('.')) {
            val ext = ctx.contentResolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrBlank()) name = "$name.$ext"
        }
        return name!!
    }

    /** The file's size in bytes, or -1 when the provider won't say. */
    fun size(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.SIZE)
                        if (i >= 0 && c.moveToFirst() && !c.isNull(i)) return c.getLong(i)
                    }
            }
        }
        return -1
    }

    /** Human-readable byte count, for the "too big" message. */
    fun formatSize(n: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v < 10 && i > 0) String.format("%.1f %s", v, units[i])
               else String.format("%.0f %s", v, units[i])
    }
}
