package com.example.ubuntuterminal

import android.app.Application
import com.example.ubuntuterminal.setup.Installer
import java.io.File

/**
 * Custom [Application] entry point: starts the dapi bridge as early as
 * possible - before any activity or service exists - so that not even the
 * most aggressive OEM service killer (ColorOS et al.) can leave a running
 * terminal without the bridge.
 */
class UbuntuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidApiBridge.start(this)

        // v1.29: a crashed format used to leave a partition-sized sparse
        // image (60+ GB apparent size) inside the app's DATA dir - storage
        // cleaners misread that as enormous app data and some of them wiped
        // the app's data (the user then lost their whole Ubuntu). Images now
        // live in cacheDir (never counted as user data); kill any strays,
        // including legacy ones from v1.28 and older.
        runCatching {
            val stale = File(cacheDir, "fmt/fmt.img")
            if (stale.isFile && System.currentTimeMillis() - stale.lastModified() > 30 * 60 * 1000L)
                stale.delete()
            File(Installer.rootfsDir(this), "tmp/fmt.img").delete()
        }
    }
}
