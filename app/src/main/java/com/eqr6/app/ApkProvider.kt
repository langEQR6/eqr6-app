package com.eqr6.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal file provider for handing a downloaded APK to the system package
 * installer.
 *
 * WHY THIS EXISTS
 * ---------------
 * Since Android 7.0 (API 24) an app may not pass a file:// URI to another app:
 * doing so throws FileUriExposedException. The usual fix is AndroidX
 * FileProvider, but this project deliberately carries no external
 * dependencies, so this is a small purpose-built equivalent.
 *
 * It exposes ONLY files under <cacheDir>/updates whose names match a strict
 * whitelist pattern, so it cannot be abused to read arbitrary app-private
 * files.
 */
class ApkProvider : ContentProvider() {

    companion object {
        private const val TAG = "ApkProvider"

        /** Safe, predictable names only - no path traversal possible. */
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")

        fun authority(context: Context): String = context.packageName + ".apkprovider"

        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(file.name)
                .build()
    }

    private fun updatesDir(context: Context): File = File(context.cacheDir, "updates")

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!SAFE_NAME.matches(name)) return null
        val file = File(updatesDir(ctx), name)
        return if (file.exists()) file else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri) ?: throw FileNotFoundException("no such apk: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** So the installer can show a sensible file name and size. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> row[i] = file.name
                OpenableColumns.SIZE -> row[i] = file.length()
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
