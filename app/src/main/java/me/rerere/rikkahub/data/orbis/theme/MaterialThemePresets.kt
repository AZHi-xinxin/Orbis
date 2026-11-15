package me.rerere.rikkahub.data.orbis.theme

/** Curated native materials. The wallpaper is a normal, user-replaceable theme asset. */
internal enum class MaterialThemePreset(val key: String, val title: String, val description: String) {
    FROSTED("frosted", "雾蓝 · 毛玻璃", "柔和雾面，让背景的光晕开"),
    LIQUID("liquid", "流光 · 液态玻璃", "清透水面，浅蓝粉紫的流动边缘"),
    LIQUID_STILL("liquid-still", "静澜 · 静态液态玻璃", "保留清透折射与浅色边缘，不持续流动"),
    ICE("ice", "霜花 · 冰玻璃", "雾面冰层，透亮而坚硬的冰晶"),
    ;
    val id get() = "orbis-material-$key"
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

internal fun materialThemePreset(preset: MaterialThemePreset, wallpaper: String): ThemePackage {
    val liquid = ThemeBubbleMaterial(type="liquid", blur=3f, depth=0f, tintOpacity=.14f,
        refraction=.9f, displacement=14f, flow=.85f, flowSpeed=.85f, metallic=.4f,
        dispersion=.55f, fold=.025f, refractionHeight=.18f, highlight=.65f, rim=.45f)
    val material = when(preset) {
        MaterialThemePreset.FROSTED -> ThemeBubbleMaterial(blur=20f,tintOpacity=.30f,highlight=.6f,rim=.35f)
        MaterialThemePreset.LIQUID -> liquid
        MaterialThemePreset.LIQUID_STILL -> liquid.copy(flow=0f,shimmer=false)
        MaterialThemePreset.ICE -> ThemeBubbleMaterial(blur=14f,tintOpacity=.28f,displacement=16f,
            refraction=.9f,highlight=.65f,rim=.4f)
    }
    val ice = if(preset==MaterialThemePreset.ICE) ThemeBubbleTexture(opacity=.92f,density=.8f,
        scale=1.1f,edgeFade=.88f,seed=19) else null
    val assistant = ThemeBubble(bg="#EEF7FF",text="#233A50",radius=28,padding=14,maxWidthRatio=.9f,
        material=material,texture=ice)
    val human = assistant.copy(bg="#F8F0FC",material=material.copy(tintOpacity=material.tintOpacity+.04f),
        texture=ice?.copy(seed=41,scale=.95f,color="#F3EEFF"))
    return ThemePackage(name=preset.title,
        colors=ThemeColors(background="#EAF1FA",surface="#F4F7FE",primary="#4C668E",
            onPrimary="#FFFFFF",text="#233A50",textSecondary="#53667D"),
        bubbles=ThemeBubbles(assistant,human),
        background=ThemeBackground(type="image",asset=wallpaper,dim=0f),assets=listOf(wallpaper),
        chrome=ThemeChrome(dock="#EEF3FC",onDock="#304966",star="#759EC6",
            composer="#F2F5FD",onComposer="#243B53",
            surface=ThemeSurface(material=material,texture=ice?.copy(scale=.75f,density=.45f,opacity=.65f)),
            composerSurface=ThemeSurface(material=material.copy(blur=if(material.type=="liquid") 5f else 16f,
                tintOpacity=if(material.type=="liquid") .2f else .34f),texture=ice?.copy(scale=.8f,density=.5f,opacity=.72f)),
            starSurface=ThemeSurface(material=liquid.copy(flow=if(preset==MaterialThemePreset.LIQUID_STILL) 0f else .5f,
                shimmer=false,metallic=.25f,displacement=9f))))
        .also { it.validate() }
}
