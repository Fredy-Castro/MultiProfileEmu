package com.multiprofileemu.platform

import com.multiprofileemu.fs.SAF_CHILD_SCHEME

actual fun isSafPath(path: String): Boolean =
    path.startsWith("content://") || path.startsWith(SAF_CHILD_SCHEME)
