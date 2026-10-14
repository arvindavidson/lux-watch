package com.luxwatch.gadget

import com.luxwatch.gadget.avatar.AvatarMode

/**
 * What Lux is doing, for the watch face. MainActivity installs these while it
 * exists (they read its Compose state, so they stay current while it's in the
 * background); the face reads them every frame.
 */
object LuxStatus {
    @Volatile var mode: () -> AvatarMode = { AvatarMode.IDLE }

    /** elapsedRealtime of the last reply (0 = none), for the happy hop. */
    @Volatile var happyAt: () -> Long = { 0L }

    fun reset() {
        mode = { AvatarMode.IDLE }
        happyAt = { 0L }
    }
}
