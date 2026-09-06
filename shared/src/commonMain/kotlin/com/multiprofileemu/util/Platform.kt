package com.multiprofileemu.util

expect fun currentEpochMillis(): Long

expect fun newId(): String

/** Root directory for this app's own config + managed profile storage. */
expect fun defaultAppDataDir(): String
