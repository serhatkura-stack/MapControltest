package com.mapcontrol.hud

/**
 * TurboDog / AMap yayın testi için ekstra kodlayıcılar (SAF). Araca yazmaz: Desay sistem uygulaması
 * com.desaysv.ivi.vds.navi'nin dinleyebileceği yayın içeriklerini (anahtar -> değer) üretir. Gönderimi
 * launchertest'teki HudTestActivity yapar.
 */
object TurboDogPayload {

    const val TURBODOG_ACTION = "turbodog.navigation.system.message"
    const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val TARGET_PACKAGE = "com.desaysv.ivi.vds.navi"

    const val CODE_TURN = 201
    const val CODE_NAV_START = 202
    const val CODE_NAV_END = 203

    const val AMAP_KEY_GUIDE = 10001
    const val AMAP_KEY_STATE = 10019
    const val AMAP_STATE_START = 8
    const val AMAP_STATE_END = 9

    const val DEFAULT_ICON = 2
    const val DEFAULT_DIST = 300
    const val DIST_STEP = 20

    /** Saniye [second] (0'dan) için kalan mesafe: her saniye [DIST_STEP] azalır, 0'ın altına inmez. */
    fun distAt(start: Int, second: Int): Int = (start - DIST_STEP * second).coerceAtLeast(0)

    /** TurboDog CODE 202: seyir başladı. */
    fun turboStart(): Map<String, Any> = mapOf("CODE" to CODE_NAV_START, "DATA" to 1)

    /** TurboDog CODE 203: seyir bitti. */
    fun turboEnd(): Map<String, Any> = mapOf("CODE" to CODE_NAV_END)

    /** TurboDog CODE 201: manevra bilgisi. [nowMs] varış zamanı için şimdiki zaman (ms). */
    fun turboTurn(icon: Int, dist: Int, nowMs: Long): Map<String, Any> = linkedMapOf(
        "CODE" to CODE_TURN,
        "CURRENT_ROAD" to "Test Cad.",
        "NEXT_ROAD" to "Deneme Sk.",
        "TURN_TYPE" to icon,
        "TURN_DIST" to dist,
        "TURN_TIME" to 30,
        "REMAINING_DIST" to 1500,
        "REMAINING_TIME" to 180,
        "TOTAL_DIST" to 2000,
        "ARRIVE_TIME" to (nowMs + 180_000L),
    )

    /** AMap KEY_TYPE 10019: durum (8 başla / 9 bitir). */
    fun amapState(state: Int): Map<String, Any> = mapOf("KEY_TYPE" to AMAP_KEY_STATE, "EXTRA_STATE" to state)

    /** AMap KEY_TYPE 10001: rehberlik. */
    fun amapGuide(icon: Int, dist: Int): Map<String, Any> = linkedMapOf(
        "KEY_TYPE" to AMAP_KEY_GUIDE,
        "ICON" to icon,
        "SEG_REMAIN_DIS" to dist,
        "ROUTE_REMAIN_DIS" to 1500,
        "ROUTE_REMAIN_TIME" to 180,
        "ROUTE_ALL_DIS" to 2000,
        "CUR_ROAD_NAME" to "Test Cad.",
        "NEXT_ROAD_NAME" to "Deneme Sk.",
        "LIMITED_SPEED" to 50,
        "CAMERA_SPEED" to 0,
    )
}
