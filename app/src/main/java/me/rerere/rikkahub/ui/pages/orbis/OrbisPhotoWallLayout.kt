package me.rerere.rikkahub.ui.pages.orbis

/** A spread always has two pages and at least one empty place to add the next photograph. */
internal fun photoWallAlbumSpreads(ids: List<String>): List<List<String?>> =
    (buildList<String?> { addAll(ids); add(null) })
        .chunked(2).map { if (it.size == 1) it + null else it }

/** Bounds the paper, not the bitmap. The image itself is always fitted completely inside it. */
internal fun photoWallFrameAspectRatio(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) .8f else (width.toFloat() / height).coerceIn(.58f, 1.8f)

/** Matches the quadratic rope drawn from y=14, via y=60, back to y=14 (in dp). */
internal fun photoWallRopeY(column: Int, columnCount: Int): Float {
    require(columnCount > 0 && column in 0 until columnCount)
    val t = (column + .5f) / columnCount
    return 14f + 92f * t * (1f - t)
}

internal fun photoWallCollageTilt(index: Int): Float =
    floatArrayOf(-2.5f, 2f, 1.5f, -1.8f, -1f, 2.8f)[Math.floorMod(index, 6)]
