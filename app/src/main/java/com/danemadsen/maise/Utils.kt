package com.danemadsen.maise

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/** Get AssetManager from package context (for install-time asset packs) or context fallback. */
fun getAssetManager(context: Context): AssetManager {
    return try {
        context.createPackageContext(context.packageName, 0).assets
    } catch (_: Exception) {
        context.assets
    }
}

/** Copy an asset to the app's files directory (cached; not re-copied on subsequent runs). */
fun copyAssetToFile(context: Context, assetPath: String): File {
    val dest = File(context.filesDir, assetPath)
    if (!dest.exists()) {
        dest.parentFile?.mkdirs()
        getAssetManager(context).open(assetPath).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }
    return dest
}

