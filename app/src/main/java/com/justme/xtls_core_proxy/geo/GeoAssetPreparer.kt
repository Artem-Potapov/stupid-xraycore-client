package com.justme.xtls_core_proxy.geo

import android.content.Context
import android.content.res.AssetManager
import java.io.File

object GeoAssetPreparer {
    internal const val REQUIRED_GEO_FILE = "geoip.dat"
    internal val GEO_ASSET_FILES = listOf(
        "geoip.dat",
        "geosite.dat",
        "geoip_IR.dat",
        "geoip_RU.dat",
        "geosite_IR.dat",
        "geosite_RU.dat"
    )

    fun prepare(context: Context): Result<File> = runCatching {
        val outputDir = File(context.filesDir, "xray-geo")
        ensureDirectory(outputDir)

        val assets = context.assets
        val availableAssets = assets.list("").orEmpty().toSet()
        for (assetName in GEO_ASSET_FILES) {
            if (!availableAssets.contains(assetName)) {
                continue
            }
            copyAssetIfNeeded(assets, assetName, File(outputDir, assetName))
        }

        val requiredFile = File(outputDir, REQUIRED_GEO_FILE)
        require(requiredFile.isFile) {
            "Missing $REQUIRED_GEO_FILE. Add it to app/src/main/assets/ (see WHERE_TO_GET_GEOFILES.md) and rebuild."
        }
        outputDir
    }

    internal fun shouldCopy(existingLength: Long?, assetLength: Long): Boolean {
        return existingLength == null || existingLength != assetLength
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Failed to create geofile directory: ${directory.absolutePath}")
        }
    }

    private fun copyAssetIfNeeded(assets: AssetManager, assetName: String, destination: File) {
        val assetLength = readAssetLength(assets, assetName)
        val currentLength = destination.takeIf { it.isFile }?.length()
        if (!shouldCopy(currentLength, assetLength)) {
            return
        }

        assets.open(assetName).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun readAssetLength(assets: AssetManager, assetName: String): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        assets.open(assetName).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
            }
        }
        return total
    }
}
