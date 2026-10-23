package com.nuvio.app.features.player.shaders

import nuvio.composeapp.generated.resources.Res

/** The notices of the bundled shaders, for Settings > About > Licenses. */
object ShaderLicenses {
    /** Shown where the shaders ship: Android. */
    val isShown: Boolean get() = ShaderFiles.isSupported

    /** The upstream commits [project]'s bundled files come from. */
    fun pins(project: ShaderProject): List<ShaderPin> =
        if (project == ShaderCatalog.shaderPack) {
            listOf(ShaderCatalog.defaultShaderPack)
        } else {
            ShaderCatalog.shaders.filter { it.project == project }.map { it.pin }.distinct()
        }

    /** [project]'s notice, followed by the LGPL text for LGPL projects. */
    suspend fun text(project: ShaderProject): String {
        val notice = Res.readBytes(project.licenseFile).decodeToString()
        if (!project.license.startsWith("LGPL")) return notice
        return notice + "\n\n" + Res.readBytes(ShaderCatalog.LGPL_FILE).decodeToString()
    }
}
