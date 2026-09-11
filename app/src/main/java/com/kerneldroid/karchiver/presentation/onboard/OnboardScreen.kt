package com.kerneldroid.karchiver.presentation.onboard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardScreen(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).systemBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.FolderOpen, null,
                modifier = Modifier.size(96.dp).padding(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("File access", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "KArchiver is a file manager and archiver. To read folders and unpack archives it needs full storage access (MANAGE_EXTERNAL_STORAGE). Without it the file list stays empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try { ctx.startActivity(intent) } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = MaterialTheme.shapes.medium),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Grant access") }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { if (Environment.isExternalStorageManager() || Build.VERSION.SDK_INT < 30) onGranted() }) {
                Text("Check again")
            }
        }
    }
}

fun hasStoragePermission(ctx: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
