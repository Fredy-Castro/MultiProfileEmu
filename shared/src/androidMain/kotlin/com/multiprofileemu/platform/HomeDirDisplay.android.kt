package com.multiprofileemu.platform

import com.multiprofileemu.fs.prettySafRootLabel

actual fun displayHomeDir(path: String): String = prettySafRootLabel(path) ?: path
