package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleDriveManager(private val context: android.content.Context) {

    private val injectedClientId: String? = try {
        val field = BuildConfig::class.java.getField("GOOGLE_DRIVE_CLIENT_ID")
        val value = field.get(null) as? String
        if (value == "YOUR_CLIENT_ID_HERE") null else value
    } catch (e: Exception) {
        null
    }
    
    private val savedClientId: String? = context.getSharedPreferences("poker_debug", android.content.Context.MODE_PRIVATE)
        .getString("drive_client_id", null)
        
    private val clientId: String? = if (!savedClientId.isNullOrEmpty()) savedClientId else injectedClientId

    fun getSignInClient(): GoogleSignInClient {
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_READONLY), Scope(DriveScopes.DRIVE))
        
        clientId?.let {
            gsoBuilder.requestIdToken(it)
        }
        
        return GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    suspend fun getDriveService(account: GoogleSignInAccount): Drive {
        return withContext(Dispatchers.IO) {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_READONLY, DriveScopes.DRIVE)
            )
            credential.selectedAccount = account.account

            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("PokerBot")
                .build()
        }
    }

    suspend fun listFilesInFolder(driveService: Drive, folderId: String): List<com.google.api.services.drive.model.File> {
        return withContext(Dispatchers.IO) {
            try {
                val result = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, mimeType)")
                    .execute()
                result.files ?: emptyList()
            } catch (e: Exception) {
                Log.e("DriveManager", "Error listing files", e)
                emptyList()
            }
        }
    }

    suspend fun renameFile(driveService: Drive, fileId: String, newName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val fileMetadata = com.google.api.services.drive.model.File()
                fileMetadata.name = newName
                driveService.files().update(fileId, fileMetadata).execute()
                true
            } catch (e: Exception) {
                Log.e("DriveManager", "Error renaming file", e)
                false
            }
        }
    }
}
