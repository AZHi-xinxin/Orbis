package me.rerere.rikkahub.data.model

/**
 * System icons follow the selected day/night theme over photos, just like the
 * rest of the controls. A photo is not evidence that its top edge is dark.
 * Only the known, image-free star backdrop overrides a light theme.
 */
fun orbisChatUsesLightStatusIcons(
    appearance: OrbisAppearance,
    darkTheme: Boolean,
): Boolean = darkTheme || (appearance.backgroundEnabled &&
    appearance.backgroundImage.isNullOrBlank() && appearance.backgroundStyle == OrbisBackgroundStyle.STARS)

/** null leaves the global theme in charge; hidden/back-stack chats never own status icons. */
fun orbisChatLightStatusBarOverride(
    orbisEnabled: Boolean,
    chatIsTopRoute: Boolean,
    homeVisible: Boolean,
    lightStatusIcons: Boolean,
    drawerVisible: Boolean = false,
): Boolean? = if (orbisEnabled && chatIsTopRoute && !homeVisible && !drawerVisible) !lightStatusIcons else null

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
