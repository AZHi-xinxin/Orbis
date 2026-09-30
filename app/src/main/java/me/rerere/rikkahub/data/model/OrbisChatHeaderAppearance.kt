package me.rerere.rikkahub.data.model

/** Keep route status icons and floating chat controls on the same contrast policy. */
fun orbisChatUsesLightHeader(
    appearance: OrbisAppearance,
    darkTheme: Boolean,
    assistantHasBackground: Boolean,
): Boolean = darkTheme || if (appearance.backgroundEnabled) {
    appearance.backgroundStyle == OrbisBackgroundStyle.STARS || !appearance.backgroundImage.isNullOrBlank()
} else assistantHasBackground

/** null leaves the global theme in charge; hidden/back-stack chats never own status icons. */
fun orbisChatLightStatusBarOverride(
    debug: Boolean,
    chatIsTopRoute: Boolean,
    homeVisible: Boolean,
    lightHeader: Boolean,
    drawerVisible: Boolean = false,
): Boolean? = if (debug && chatIsTopRoute && !homeVisible && !drawerVisible) !lightHeader else null

/** Keep the drawer's palette in charge throughout opening, dragging, and closing. */
fun orbisChatDrawerVisible(
    permanent: Boolean,
    currentOpen: Boolean,
    targetOpen: Boolean,
    offsetPx: Float,
    widthPx: Int,
): Boolean = permanent || currentOpen || targetOpen ||
    (widthPx > 0 && offsetPx.isFinite() && offsetPx > -widthPx + 1f)

/**
 * Pass SheetState.requireOffset(), not an external modifier's local/window coordinates.
 * Material's Expanded anchor is max(0, viewportHeight - sheetHeight), not always zero.
 */
fun orbisSheetLightStatusBars(
    activityLightStatusBars: Boolean,
    sheetLightStatusBars: Boolean,
    sheetOffsetPx: Float?,
    statusBarHeightPx: Int,
): Boolean = if (sheetOffsetPx != null && sheetOffsetPx.isFinite() &&
    sheetOffsetPx <= statusBarHeightPx.coerceAtLeast(0).toFloat()) {
    sheetLightStatusBars
} else activityLightStatusBars
