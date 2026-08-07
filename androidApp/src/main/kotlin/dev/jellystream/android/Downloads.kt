package dev.jellystream.android

import android.content.Context
import dev.jellystream.shared.BaseItem
import dev.jellystream.shared.DownloadState
import dev.jellystream.shared.DownloadedItem
import dev.jellystream.shared.JellyfinApi
import dev.jellystream.shared.PersistedDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App-private storage for the downloads blob (the shared module owns the
 * JSON). One file per profile: two accounts sharing a tablet do not share
 * a watchlist, and one signing out must not take the other's files.
 */
class DownloadStore(context: Context, profileKey: String) {
    private val prefs = context.getSharedPreferences("jellystream_downloads", Context.MODE_PRIVATE)
    private val key = "downloads:$profileKey"

    fun load(): PersistedDownloads =
        prefs.getString(key, null)?.let { PersistedDownloads.fromJson(it) }
        // A corrupt blob costs the list, not the launch — the files are
        // still on disk and can be fetched again
            ?: PersistedDownloads.empty()

    fun save(downloads: PersistedDownloads) {
        prefs.edit().putString(key, downloads.toJson()).apply()
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}

/** Where a profile's files live. Private storage: no media-scanner, no gallery. */
fun downloadsDir(context: Context, profileKey: String): File =
    File(context.filesDir, "downloads/${profileKey.hashCode()}").apply { mkdirs() }

fun downloadedFile(context: Context, profileKey: String, item: DownloadedItem): File =
    File(downloadsDir(context, profileKey), item.fileName)

/**
 * Fetches original files, one at a time, and reports progress as it goes.
 *
 * Deliberately not DownloadManager: that would put the token in a URL the
 * system keeps, and hand the file to shared storage where anything can
 * read it. This streams into app-private storage with the token in a
 * header, the same way playback does.
 */
class Downloader(
    private val context: Context,
    private val api: JellyfinApi,
    private val profileKey: String,
    private val store: DownloadStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called on a background thread whenever the list changes. */
    var onChange: ((PersistedDownloads) -> Unit)? = null

    fun start(item: BaseItem, container: String?) {
        val queued = DownloadedItem.queued(item, container)
        publish(store.load().with(queued))
        scope.launch { run(queued) }
    }

    /** Drops the record and the bytes; a half file is worth nothing. */
    fun remove(itemId: String) {
        val downloads = store.load()
        downloads[itemId]?.let { item ->
            runCatching { downloadedFile(context, profileKey, item).delete() }
        }
        publish(downloads.without(itemId))
    }

    private fun publish(downloads: PersistedDownloads) {
        store.save(downloads)
        onChange?.invoke(downloads)
    }

    private suspend fun run(item: DownloadedItem) = withContext(Dispatchers.IO) {
        val url = api.downloadUrl(item.itemId)
        val auth = api.streamAuthorizationHeader()
        if (url == null) {
            publish(store.load().with(item.copy(state = DownloadState.FAILED)))
            return@withContext
        }
        val target = downloadedFile(context, profileKey, item)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                // Same rule as playback: the token is a header, never a URL
                auth?.let { setRequestProperty("Authorization", it) }
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            if (connection.responseCode !in 200..299) {
                publish(store.load().with(item.copy(state = DownloadState.FAILED)))
                return@withContext
            }
            val total = connection.contentLengthLong
            publish(
                store.load().with(
                    item.copy(state = DownloadState.DOWNLOADING, totalBytes = total)
                )
            )

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPublished = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        // Publishing every chunk would write prefs hundreds
                        // of times a second; every megabyte is plenty for a
                        // progress bar
                        if (written - lastPublished > 1_000_000) {
                            lastPublished = written
                            publish(
                                store.load().with(
                                    item.copy(
                                        state = DownloadState.DOWNLOADING,
                                        totalBytes = total,
                                        downloadedBytes = written,
                                    )
                                )
                            )
                        }
                    }
                    publish(
                        store.load().with(
                            item.copy(
                                state = DownloadState.COMPLETE,
                                totalBytes = if (total > 0) total else written,
                                downloadedBytes = written,
                            )
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A partial file is not half a film; it must not look playable
            runCatching { target.delete() }
            publish(store.load().with(item.copy(state = DownloadState.FAILED)))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Tells the server about positions watched with the network off.
     *
     * Failure is silent and leaves the entry unsynced: the next launch
     * tries again, and losing a resume point is worse than a retry.
     */
    fun syncPositions() {
        scope.launch {
            val downloads = store.load()
            downloads.unsyncedPositions.forEach { item ->
                val ok = runCatching {
                    api.reportPlaybackStopped(item.itemId, item.positionTicks, null)
                }.isSuccess
                if (ok) publish(store.load().markSynced(item.itemId))
            }
        }
    }
}
