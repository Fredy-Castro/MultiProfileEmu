package com.multiprofileemu.platform;

// Bound via Shizuku.bindUserService — see ShizukuBridge.kt. Implemented by
// PrivilegedFileOpsService, which Shizuku instantiates in a separate process running as adb
// shell's UID, not in this app's own process.
interface IPrivilegedFileOps {
    /** Recursively copies sourcePath's contents into destPath. Returns 0 on success, -1 on failure. */
    int copyDirectory(String sourcePath, String destPath);

    /** Lets ShizukuBridge tear down the remote process once it's done, rather than leaving it running. */
    void destroy();
}
