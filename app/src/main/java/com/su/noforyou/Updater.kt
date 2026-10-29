package com.su.noforyou

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks the latest GitHub release of this repo and installs its APK (Android asks you to confirm the install). */
object Updater {
    private const val LATEST = "https://api.github.com/repos/adamcz5/x.com-without-for-you-page/releases/latest"

    class Release(val build: Int, val url: String)

    /** Blocking: call off the main thread. */
    fun latest(): Release {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode != 200) throw IOException("GitHub answered ${conn.responseCode} (is the repo public, and is there a release?)")
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val build = json.getString("tag_name").removePrefix("build-").toInt()
        val assets = json.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        return Release(build, url ?: throw IOException("The latest release has no APK"))
    }

    /** Blocking: downloads the APK and hands it to the system installer. */
    fun downloadAndInstall(context: Context, release: Release) {
        val file = File(context.cacheDir, "update.apk")
        val conn = URL(release.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }

        // hand the file to the system's install screen (installing it through our own session is refused for self-updates)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://com.su.noforyou.apk/update.apk"), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Serves the downloaded APK (cache/update.apk) to the system installer. */
class ApkProvider : ContentProvider() {
    private fun apk() = File(context!!.cacheDir, "update.apk")
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(apk(), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("update.apk", apk().length())) }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

