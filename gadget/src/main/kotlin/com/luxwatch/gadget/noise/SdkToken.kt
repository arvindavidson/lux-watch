package com.luxwatch.gadget.noise

/** The gadget SDK token (`mgst_…`) issued at gadgets.muse.ai/settings/sdk-tokens. */
object SdkToken {
    // mgst_ plus 43 canonical base64url characters (SDK config.py _SDK_TOKEN).
    private val PATTERN = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
    private val IN_TEXT = Regex("(?<![A-Za-z0-9_-])${PATTERN.pattern}(?![A-Za-z0-9_-])")

    fun isValid(token: String): Boolean = PATTERN.matches(token)

    /** The one token inside pasted text (people paste whole lines, keyboards add spaces), or null. */
    fun find(text: String): String? = IN_TEXT.findAll(text).singleOrNull()?.value
}
