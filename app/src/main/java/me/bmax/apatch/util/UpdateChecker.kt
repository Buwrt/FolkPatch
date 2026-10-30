package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import me.bmax.apatch.BuildConfig

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    // The update check now reads this fork's own releases, so a published
    // version here is what the app compares against.
    private const val UPDATE_API_URL = "https://api.github.com/repos/Buwrt/FolkPatch/releases/latest"
    private const val UPDATE_URL = "https://github.com/Buwrt/FolkPatch/releases"
    private val TAG_NAME_REGEX = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    suspend fun checkUpdate(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val result = FolkApiClient.fetchJson(
                    UPDATE_API_URL,
                    ttlMs = 30 * 60 * 1000L,
                    maxRetries = 1,
                    forceRefresh = true
                )
                val rawResponse = result.getOrNull() ?: return@withContext false

                // The release tag carries the version code, e.g. "v115115".
                val remoteVersionCodeStr = TAG_NAME_REGEX
                    .find(rawResponse.replace("\uFEFF", ""))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.filter(Char::isDigit)
                    .orEmpty()
                Log.d(TAG, "Parsed version string: '$remoteVersionCodeStr'")

                val remoteVersionCode = remoteVersionCodeStr.toIntOrNull()
                if (remoteVersionCode != null && remoteVersionCodeStr.isNotEmpty()) {
                    Log.d(TAG, "Remote: $remoteVersionCode, Local: ${BuildConfig.VERSION_CODE}")
                    return@withContext remoteVersionCode > BuildConfig.VERSION_CODE
                } else {
                    Log.e(TAG, "Failed to parse version code")
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Check update failed", e)
                false
            }
        }
    }

    fun openUpdateUrl(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
