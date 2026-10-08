package com.example.wifiscanner

import android.app.Application

/**
 * Application class (declared in the manifest as `android:name`).
 *
 * Currently a lightweight hook for future app-wide singletons (e.g. a
 * process-scoped OUI table or WorkManager initializer). Kept minimal so
 * cold-start stays fast.
 */
class WifiScannerApp : Application()
