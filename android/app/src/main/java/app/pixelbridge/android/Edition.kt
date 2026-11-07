package app.pixelbridge.android

/** Which build this is: "full", or "lite" (no SMS, notifications or call features). */
object Edition {
    val isLite: Boolean get() = BuildConfig.FLAVOR == "lite"
}
