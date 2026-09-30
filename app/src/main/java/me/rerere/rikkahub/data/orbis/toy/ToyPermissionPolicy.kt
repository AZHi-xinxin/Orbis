package me.rerere.rikkahub.data.orbis.toy

/** Pure runtime-permission policy. Legacy BLUETOOTH permissions are granted from the manifest. */
internal object ToyPermissionPolicy {
    private const val SCAN = "android.permission.BLUETOOTH_SCAN"
    private const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    private const val LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    // Our discovery UI also reads device names and the adapter's enabled state, requiring CONNECT.
    fun scanPermissions(apiLevel: Int): List<String> =
        if (apiLevel >= 31) listOf(SCAN, CONNECT) else listOf(LOCATION)

    fun connectionPermissions(apiLevel: Int): List<String> =
        if (apiLevel >= 31) listOf(CONNECT) else emptyList()

    fun canScan(apiLevel: Int, isGranted: (String) -> Boolean): Boolean =
        scanPermissions(apiLevel).all(isGranted)

    // Includes every GATT write, especially STOP; discovery permission is not needed here.
    fun canConnect(apiLevel: Int, isGranted: (String) -> Boolean): Boolean =
        connectionPermissions(apiLevel).all(isGranted)
}
