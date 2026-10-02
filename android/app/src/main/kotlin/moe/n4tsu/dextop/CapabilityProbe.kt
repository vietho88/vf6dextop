package moe.n4tsu.dextop

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.SurfaceControl

internal data class CapabilityProbeResult(
    val supported: Boolean,
    val detail: String
)

/** Read-only probes. Version/manufacturer is never treated as proof of support. */
internal class CapabilityProbe(
    private val context: Context,
    private val privilegedAccess: PrivilegedAccess
) {
    fun run(): Map<String, CapabilityProbeResult> = linkedMapOf(
        "privilegedAccess" to CapabilityProbeResult(privilegedAccess.isAvailable(), "Shizuku binder"),
        "overlayDisplaySetting" to probe("Settings.Global overlay_display_devices") {
            Settings.Global.getString(context.contentResolver, DisplayMirrorBackend.DISPLAY_SPECIFICATION)
        },
        "displayManager" to probe("DisplayManager") {
            context.getSystemService(DisplayManager::class.java).displays.size
        },
        "windowManagerMirror" to probe("IWindowManager.mirrorDisplay") {
            Class.forName("android.view.IWindowManager").getMethod(
                "mirrorDisplay", Int::class.javaPrimitiveType, SurfaceControl::class.java
            )
        },
        "surfaceControlMirror" to probe("SurfaceControl.mirrorDisplay") {
            SurfaceControl::class.java.getDeclaredMethod("mirrorDisplay", Int::class.javaPrimitiveType)
        },
        "surfaceControlTransaction" to probe("SurfaceControl.Transaction transforms") {
            SurfaceControl.Transaction::class.java.getMethod(
                "setWindowCrop", SurfaceControl::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
        }
    )

    private inline fun probe(detail: String, block: () -> Any?): CapabilityProbeResult =
        runCatching { block(); CapabilityProbeResult(true, detail) }
            .getOrElse { CapabilityProbeResult(false, "$detail: ${it.javaClass.simpleName}: ${it.message}") }
}
