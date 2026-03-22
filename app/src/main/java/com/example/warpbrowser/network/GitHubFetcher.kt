package com.example.warpbrowser.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GitHubFetcher {

    // Simple cache for quick re-access
    private val cache = mutableMapOf<String, CachedResponse>()
    private val cacheMaxAgeMs = 30000L // 30 seconds cache

    data class CachedResponse(val content: String, val timestamp: Long)

    /**
     * Fetches raw content from a GitHub URL.
     * Converts github.com URLs to raw.githubusercontent.com URLs.
     */
    suspend fun fetchRawContent(githubUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rawUrl = convertToRawUrl(githubUrl)

            // Check cache first
            val cached = cache[rawUrl]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < cacheMaxAgeMs) {
                return@withContext Result.success(cached.content)
            }

            var connection: HttpURLConnection? = null
            try {
                connection = URL(rawUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000  // Reduced from 15s
                connection.readTimeout = 10000    // Reduced from 15s
                connection.instanceFollowRedirects = true
                connection.useCaches = false

                val responseCode = connection.responseCode

                // Handle redirect manually if needed
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (redirectUrl != null) {
                        connection.disconnect()
                        connection = URL(redirectUrl).openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 8000
                        connection.readTimeout = 10000
                        connection.instanceFollowRedirects = true

                        val redirectResponseCode = connection.responseCode
                        if (redirectResponseCode == HttpURLConnection.HTTP_OK) {
                            val content = readContentFast(connection)
                            cache[rawUrl] = CachedResponse(content, System.currentTimeMillis())
                            return@withContext Result.success(content)
                        } else {
                            return@withContext Result.failure(Exception("Failed to fetch content: HTTP $redirectResponseCode"))
                        }
                    }
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val content = readContentFast(connection)
                    cache[rawUrl] = CachedResponse(content, System.currentTimeMillis())
                    Result.success(content)
                } else {
                    Result.failure(Exception("Failed to fetch content: HTTP $responseCode"))
                }
            } finally {
                connection?.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network error: ${e.message}", e))
        }
    }

    /**
     * Fast content reading with larger buffer
     */
    private fun readContentFast(connection: HttpURLConnection): String {
        val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8), 8192)
        val builder = StringBuilder()
        val buffer = CharArray(2048)
        var read: Int
        while (reader.read(buffer).also { read = it } != -1) {
            builder.append(buffer, 0, read)
        }
        reader.close()
        return builder.toString()
    }

    /**
     * Converts a GitHub blob URL to a raw content URL.
     * Example:
     *   https://github.com/user/repo/blob/main/file.warp
     *   -> https://raw.githubusercontent.com/user/repo/main/file.warp
     */
    private fun convertToRawUrl(githubUrl: String): String {
        // Remove any trailing parameters or fragments
        val cleanUrl = githubUrl.split("?")[0].split("#")[0]

        val pattern = Regex("""https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)""")
        val match = pattern.find(cleanUrl)

        return if (match != null) {
            val (owner, repo, ref, path) = match.destructured
            "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"
        } else {
            cleanUrl
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
