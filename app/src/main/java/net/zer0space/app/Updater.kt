package net.zer0space.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * In-app updates.
 *
 * The dashboard tells the page which release is current (src/app_release.py
 * reads it from GitHub); the page hands URL + SHA-256 + versionCode here.
 * Before Android's installer is even opened, four checks:
 *
 *  1. the URL is a release asset of this app's own repository (compiled in);
 *  2. it is newer than what is installed (no downgrades);
 *  3. the downloaded file matches the SHA-256 the dashboard published;
 *  4. the APK has this app's package name AND is signed with the same key.
 *
 * Android itself refuses an update signed with a different key, but check 4
 * also stops a *different* app (another package name, which Android would
 * happily install side by side) from being pushed through this button.
 * The user still confirms every install in the system dialog; Android does
 * not allow silent installs outside an app store, and that is fine.
 */
class Updater(private val activity: Activity, private val emit: (JSONObject) -> Unit) {

    @Volatile
    private var running = false

    suspend fun install(url: String, sha256: String, versionCode: Long): JSONObject {
        if (running) throw IOException("an update is already running")
        if (!url.startsWith(BuildConfig.RELEASE_PREFIX) || !url.endsWith(".apk") || url.contains("..")) {
            throw IOException("not an official release URL")
        }
        if (versionCode <= currentVersionCode()) throw IOException("not newer than the installed version")
        val expectedSha = sha256.lowercase().takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

        if (!activity.packageManager.canRequestPackageInstalls()) {
            emit(JSONObject().put("state", "needs-permission"))
            withContext(Dispatchers.Main) {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            }
            return JSONObject().put("ok", false).put("state", "needs-permission")
        }

        running = true
        try {
            val file = withContext(Dispatchers.IO) { download(url, expectedSha) }
            emit(JSONObject().put("state", "verifying"))
            withContext(Dispatchers.IO) { verify(file, versionCode) }
            emit(JSONObject().put("state", "installing"))
            withContext(Dispatchers.Main) { launchInstaller(file) }
            return JSONObject().put("ok", true)
        } catch (e: Exception) {
            emit(JSONObject().put("state", "error").put("error", e.message ?: e.toString()))
            throw e
        } finally {
            running = false
        }
    }

    private fun download(url: String, expectedSha: String?): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "zer0space-update.apk")
        val req = Request.Builder().url(url).header("User-Agent", "zer0space-android/${BuildConfig.VERSION_NAME}").build()
        Http.stream.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("empty download")
            val total = body.contentLength()
            if (total > MAX_APK) throw IOException("download too large")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (read > MAX_APK) throw IOException("download too large")
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastEmit > 250) {
                            lastEmit = now
                            emit(JSONObject().put("state", "downloading").put("progress", read.toDouble() / total))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha != null && actual != expectedSha) {
                target.delete()
                throw IOException("checksum mismatch")
            }
        }
        return target
    }

    private fun verify(file: File, expectedCode: Long) {
        val pm = activity.packageManager
        val archive = archiveInfo(pm, file.absolutePath) ?: throw IOException("not a valid APK")
        if (archive.packageName != activity.packageName) throw IOException("APK is a different app")
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archiveCode <= currentVersionCode()) throw IOException("APK is not newer")
        if (archiveCode != expectedCode) throw IOException("APK version does not match the release")
        val installedSigners = signers(installedInfo(pm))
        val archiveSigners = signers(archive)
        if (installedSigners.isEmpty() || installedSigners != archiveSigners) {
            throw IOException("APK is not signed with the zer0space key")
        }
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun currentVersionCode(): Long {
        val info = installedInfo(activity.packageManager)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    companion object {
        private const val MAX_APK = 200L * 1024 * 1024
    }
}
