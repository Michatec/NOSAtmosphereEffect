package com.app.nosatmosphereeffect.helper

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.app.nosatmosphereeffect.BuildConfig
import com.app.nosatmosphereeffect.storage.ActiveFolderWatch
import com.app.nosatmosphereeffect.storage.PlaylistCollectionStore
import com.app.nosatmosphereeffect.storage.PlaylistImageSource
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.storage.WatchedFolder
import java.io.File

internal data class MediaFolder(
    val id: String,
    val name: String,
    val imageCount: Int,
    val cover: Uri?
)

internal data class MediaImage(
    val id: Long,
    val uri: Uri
)

/**
 * Folder playlists: reads device folders (MediaStore image buckets) and keeps
 * the active playlist in sync with them. Only the `folder` build flavor
 * declares the photo permissions this needs; everywhere else
 * [isAvailable] is false and the UI never offers it.
 */
internal object FolderPlaylistSource {
    private const val TAG = "FolderPlaylistSource"

    val isAvailable: Boolean get() = BuildConfig.FOLDER_PLAYLISTS

    /** Everything to request; the system shows its own full/partial choice. */
    fun requestedPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    /** Full photo access, required to notice images added later. */
    fun hasFullAccess(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Android 14+ "Select photos": only the picked images are visible. */
    fun hasPartialAccessOnly(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return !hasFullAccess(context) &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun listFolders(context: Context): List<MediaFolder> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val folders = LinkedHashMap<String, MediaFolder>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(bucketColumn) ?: continue
                    val existing = folders[bucketId]
                    folders[bucketId] = existing?.copy(imageCount = existing.imageCount + 1)
                        ?: MediaFolder(
                            id = bucketId,
                            name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank)
                                ?: "Unnamed folder",
                            imageCount = 1,
                            cover = imageUri(cursor.getLong(idColumn))
                        )
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Photo access was denied while listing folders", error)
        }
        return folders.values.sortedBy { it.name.lowercase() }
    }

    /** Images in [folderIds], oldest first so playlists keep a stable order. */
    fun imagesIn(context: Context, folderIds: Collection<String>): List<MediaImage> {
        if (folderIds.isEmpty()) return emptyList()
        val placeholders = folderIds.joinToString(",") { "?" }
        val images = mutableListOf<MediaImage>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)",
                folderIds.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    images += MediaImage(id, imageUri(id))
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Photo access was denied while reading folders", error)
        }
        return images
    }

    /**
     * Adds images that appeared in the active playlist's watched folders since
     * the last check. Returns how many were added. Call off the main thread.
     */
    fun syncActivePlaylist(context: Context, targetWidth: Int, targetHeight: Int): Int {
        if (!isAvailable || !hasFullAccess(context)) return 0
        return WallpaperStorageCoordinator.runExclusive {
            if (PlaylistModeManager.getMode(context) != PlaylistModeManager.MODE_STANDARD) {
                return@runExclusive 0
            }
            val watch = ActiveFolderWatch.read(context)
            if (watch.isEmpty) return@runExclusive 0

            val current = imagesIn(context, watch.folders.map(WatchedFolder::id))
            val fresh = current.filter { it.id !in watch.knownMediaIds }
            if (fresh.isEmpty()) return@runExclusive 0

            val fitMode = WallpaperFitHelper.getDefaultFitMode(context)
            val fillMode = WallpaperFitHelper.getDefaultFillMode(context)
            val added = PlaylistCollectionStore.append(
                context = context,
                items = fresh.map { image ->
                    PlaylistImageSource(
                        originalUri = image.uri,
                        isEdited = false,
                        editedFilePath = null,
                        matrixState = null,
                        fitMode = fitMode,
                        fillMode = fillMode
                    )
                },
                playlistDirectory = PlaylistModeManager.standardPlaylistDir(context),
                originalsDirectory = File(
                    context.filesDir,
                    PlaylistModeManager.STANDARD_ORIGINALS_DIR
                ),
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
            // Record every id seen, including ones that failed to decode, so a
            // broken file is not retried on every launch.
            val updated = watch.copy(knownMediaIds = watch.knownMediaIds + fresh.map(MediaImage::id))
            ActiveFolderWatch.write(context, updated)
            if (added > 0) {
                SavedPlaylistLibrary.activeId(context)?.let { id ->
                    runCatching {
                        SavedPlaylistLibrary.saveActive(context, id, name = null, watch = updated)
                    }.onFailure { error ->
                        Log.w(TAG, "Could not refresh the saved copy of the playlist", error)
                    }
                }
            }
            added
        }
    }

    fun knownIdsFor(context: Context, folders: List<WatchedFolder>): Set<Long> {
        return imagesIn(context, folders.map(WatchedFolder::id)).map(MediaImage::id).toSet()
    }

    private fun imageUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}
