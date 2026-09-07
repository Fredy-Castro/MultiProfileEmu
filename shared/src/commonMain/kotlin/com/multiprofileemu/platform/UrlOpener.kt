package com.multiprofileemu.platform

/**
 * Opens [url] in the system's default browser. Only meaningful on platforms where nothing else
 * already opens it — rclone's own Drive-authorize flow opens the browser itself on desktop, so
 * that actual is a no-op to avoid popping a second, redundant tab.
 */
expect fun openUrlInBrowser(url: String)
