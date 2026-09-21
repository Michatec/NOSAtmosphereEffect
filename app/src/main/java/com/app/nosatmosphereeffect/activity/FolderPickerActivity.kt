package com.app.nosatmosphereeffect.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.nosatmosphereeffect.helper.FolderPlaylistSource
import com.app.nosatmosphereeffect.helper.MediaFolder
import com.app.nosatmosphereeffect.storage.WatchedFolder
import com.app.nosatmosphereeffect.ui.screens.FolderAccessState
import com.app.nosatmosphereeffect.ui.screens.FolderPickerScreen
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Picks the device folders a playlist should follow. Photo access is
 * requested every time this opens, so a previously denied or "Select photos"
 * grant can be upgraded whenever the user sets up a folder playlist.
 *
 * Started for a result (from the playlist editor) it returns the folders;
 * otherwise it opens the playlist editor with them.
 */
class FolderPickerActivity : ComponentActivity() {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var access by mutableStateOf(FolderAccessState.CHECKING)
    private var canAskAgain by mutableStateOf(true)
    private var folders by mutableStateOf<List<MediaFolder>?>(null)
    private var selectedIds by mutableStateOf<Set<String>>(emptySet())
    private var requestStartedAt = 0L

    private val requestAccess =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshAccess(afterRequest = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!FolderPlaylistSource.isAvailable) {
            finish()
            return
        }
        selectedIds = savedInstanceState?.getStringArrayList(STATE_SELECTED)?.toSet()
            ?: intent.getStringArrayListExtra(EXTRA_FOLDER_IDS)?.toSet().orEmpty()

        setContent {
            AtmoEngineTheme {
                FolderPickerScreen(
                    access = access,
                    canAskAgain = canAskAgain,
                    folders = folders,
                    selectedIds = selectedIds,
                    onToggle = { folder ->
                        selectedIds = if (folder.id in selectedIds) {
                            selectedIds - folder.id
                        } else {
                            selectedIds + folder.id
                        }
                    },
                    onRequestAccess = ::askForAccess,
                    onOpenSettings = ::openAppSettings,
                    onContinue = ::finishWithSelection,
                    onBack = { finish() }
                )
            }
        }

        // Ask on every visit, not only the first: this is the moment the user
        // is deciding to follow folders, so a past denial should not stick.
        if (savedInstanceState == null) askForAccess()
    }

    override fun onResume() {
        super.onResume()
        // Returning from app settings.
        if (access != FolderAccessState.CHECKING) refreshAccess(afterRequest = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selectedIds))
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun askForAccess() {
        access = FolderAccessState.CHECKING
        requestStartedAt = SystemClock.uptimeMillis()
        requestAccess.launch(FolderPlaylistSource.requestedPermissions(this))
    }

    private fun refreshAccess(afterRequest: Boolean) {
        access = when {
            FolderPlaylistSource.hasFullAccess(this) -> FolderAccessState.GRANTED
            FolderPlaylistSource.hasPartialAccessOnly(this) -> FolderAccessState.PARTIAL
            else -> FolderAccessState.DENIED
        }
        if (afterRequest && access != FolderAccessState.GRANTED) {
            // Once the user has refused often enough, Android answers
            // instantly without showing any dialog. Only offer "Allow access"
            // while a dialog can still appear; app settings always works.
            val answeredWithoutDialog =
                SystemClock.uptimeMillis() - requestStartedAt < NO_DIALOG_THRESHOLD_MS
            canAskAgain = !answeredWithoutDialog ||
                shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (access == FolderAccessState.GRANTED && folders == null) loadFolders()
    }

    private fun loadFolders() {
        ioExecutor.execute {
            val loaded = runCatching { FolderPlaylistSource.listFolders(this) }
                .onFailure { error -> Log.e(TAG, "Could not list photo folders", error) }
                .getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) folders = loaded }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }

    private fun finishWithSelection() {
        val chosen = folders.orEmpty()
            .filter { it.id in selectedIds }
            .map { WatchedFolder(it.id, it.name) }
        if (chosen.isEmpty()) return
        val ids = ArrayList(chosen.map(WatchedFolder::id))
        val names = ArrayList(chosen.map(WatchedFolder::name))
        if (callingActivity != null) {
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra(EXTRA_FOLDER_IDS, ids)
                    .putStringArrayListExtra(EXTRA_FOLDER_NAMES, names)
            )
        } else {
            startActivity(
                Intent(this, PlaylistEditorActivity::class.java)
                    .putExtra("EFFECT_ID", intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL")
                    .putStringArrayListExtra(EXTRA_FOLDER_IDS, ids)
                    .putStringArrayListExtra(EXTRA_FOLDER_NAMES, names)
            )
        }
        finish()
    }

    companion object {
        private const val TAG = "FolderPicker"
        private const val STATE_SELECTED = "selected_folders"
        private const val NO_DIALOG_THRESHOLD_MS = 350L
        const val EXTRA_FOLDER_IDS = "FOLDER_IDS"
        const val EXTRA_FOLDER_NAMES = "FOLDER_NAMES"

        fun intent(context: Context, effectId: String): Intent =
            Intent(context, FolderPickerActivity::class.java).putExtra("EFFECT_ID", effectId)

        internal fun foldersFrom(data: Intent?): List<WatchedFolder> {
            val ids = data?.getStringArrayListExtra(EXTRA_FOLDER_IDS).orEmpty()
            val names = data?.getStringArrayListExtra(EXTRA_FOLDER_NAMES).orEmpty()
            return ids.mapIndexed { index, id -> WatchedFolder(id, names.getOrElse(index) { id }) }
        }
    }
}
