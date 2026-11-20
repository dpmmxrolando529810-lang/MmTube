package com.mmtube.app.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Turns a SAF "tree" URI into a MediaStore relative path.
 *
 * MediaStore is the only reliable way to write into a user-chosen folder on
 * modern Android, so we only support the primary shared-storage provider.
 * Anything else (cloud drives, SD-card providers) falls back to the default
 * MMTube folder rather than writing to a path we cannot actually own.
 */
object DownloadFolderResolver {

    fun relativeFromTreeUri(context: Context, uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val separator = docId.indexOf(':')
        if (separator < 0) return null
        val storage = docId.substring(0, separator)
        if (!storage.equals("primary", ignoreCase = true)) return null
        return docId.substring(separator + 1)
            .trim('/')
            .takeIf { it.isNotBlank() && !it.contains("..") }
    }
}
