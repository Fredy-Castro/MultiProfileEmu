package com.multiprofileemu.platform

/**
 * True when the platform's shared/external storage doesn't reliably support renaming a file
 * (confirmed on real Android hardware: `rename ... : operation not permitted` finishing a cloud
 * download into a folder resolved from a SAF pick via [resolveRealPath] — Android's scoped-storage
 * FUSE layer allows creating/writing a new file there but not renaming one, even though it's the
 * app's own SAF grant). rclone normally downloads to a `.partial` file and renames it into place
 * for atomicity; when this is true, [com.multiprofileemu.domain.CloudSyncEngine.sync] passes
 * `--inplace` instead to write the destination file directly, trading that atomicity (a crash
 * mid-transfer can leave a corrupted file instead of leaving the old one untouched) for actually
 * being able to finish at all. Desktop's real filesystem paths always support rename, so this only
 * needs to be true on Android.
 */
expect val storageRenameUnreliable: Boolean
