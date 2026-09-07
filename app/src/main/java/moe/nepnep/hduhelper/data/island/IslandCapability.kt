package moe.nepnep.hduhelper.data.island

/** A saved switch is intent, never evidence that privileged hooks are available. */
data class IslandCapability(
    val supported: Boolean = false,
    val framework: Boolean = false,
    val scoped: Boolean = false,
    val hookReady: Boolean = false,
    val muteSupported: Boolean = false,
    val mutedByModule: Boolean = false,
    val ringerSilent: Boolean = false,
    val muteKeys: Set<String> = emptySet(),
) {
    val visible: Boolean get() = supported && framework && scoped
    val ready: Boolean get() = visible && hookReady
}
