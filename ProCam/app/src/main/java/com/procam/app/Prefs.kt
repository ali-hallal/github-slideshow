package com.procam.app

import android.content.Context

/** Everything ProCam remembers between launches. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("procam", Context.MODE_PRIVATE)

    /** The first-launch welcome guide has been shown. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** The microphone permission was asked for once already. */
    var audioAsked: Boolean
        get() = sp.getBoolean("audioAsked", false)
        set(v) = sp.edit().putBoolean("audioAsked", v).apply()

    /** Record video in 4K when the phone can (otherwise 1080p). */
    var video4k: Boolean
        get() = sp.getBoolean("video4k", false)
        set(v) = sp.edit().putBoolean("video4k", v).apply()

    /** Steady video (electronic stabilization, slightly narrower view). */
    var stabilize: Boolean
        get() = sp.getBoolean("stabilize", true)
        set(v) = sp.edit().putBoolean("stabilize", v).apply()

    /** Auto mode uses HDR automatically for backlit, high-contrast scenes. */
    var autoHdr: Boolean
        get() = sp.getBoolean("autoHdr", true)
        set(v) = sp.edit().putBoolean("autoHdr", v).apply()

    /** Test hook: treat every Auto scene as needing HDR (used by automated tests only). */
    val forceHdr: Boolean get() = sp.getBoolean("forceHdr", false)

    // ---- mode
    /** false = Simple (beginner) screen, true = Pro controls. Simple is the default. */
    var uiPro: Boolean
        get() = sp.getBoolean("uiPro", false)
        set(v) = sp.edit().putBoolean("uiPro", v).apply()

    /** Simple-mode intent: "AUTO" or "GROUP". */
    var intent: String
        get() = sp.getString("intent", "AUTO") ?: "AUTO"
        set(v) = sp.edit().putString("intent", v).apply()

    /** Portrait background blur: 0 soft, 1 medium, 2 strong. */
    var portraitStrength: Int
        get() = sp.getInt("portraitStrength", 1)
        set(v) = sp.edit().putInt("portraitStrength", v).apply()

    /** Silky strength: 0 softer (8 frames), 1 medium (12), 2 dreamy (16). */
    var silkyStrength: Int
        get() = sp.getInt("silkyStrength", 1)
        set(v) = sp.edit().putInt("silkyStrength", v).apply()

    // ---- quick settings
    var grid: Boolean
        get() = sp.getBoolean("grid", true)
        set(v) = sp.edit().putBoolean("grid", v).apply()
    var level: Boolean
        get() = sp.getBoolean("level", true)
        set(v) = sp.edit().putBoolean("level", v).apply()
    var timerSeconds: Int
        get() = sp.getInt("timer", 0)
        set(v) = sp.edit().putInt("timer", v).apply()

    /** 0 = off, 1 = 95 %, 2 = 100 % (clipped). */
    var zebra: Int
        get() = sp.getInt("zebra", 0)
        set(v) = sp.edit().putInt("zebra", v).apply()

    /** 0 = only while focusing manually, 1 = always, 2 = off. */
    var peaking: Int
        get() = sp.getInt("peaking", 0)
        set(v) = sp.edit().putInt("peaking", v).apply()

    var location: Boolean
        get() = sp.getBoolean("location", false)
        set(v) = sp.edit().putBoolean("location", v).apply()
    /** Wait (briefly) for steady hands before taking the photo. */
    var steadyShot: Boolean
        get() = sp.getBoolean("steadyShot", true)
        set(v) = sp.edit().putBoolean("steadyShot", v).apply()

    var shutterSound: Boolean
        get() = sp.getBoolean("shutterSound", true)
        set(v) = sp.edit().putBoolean("shutterSound", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** 0 = take photo, 1 = zoom, 2 = normal volume. */
    var volumeKeys: Int
        get() = sp.getInt("volumeKeys", 0)
        set(v) = sp.edit().putInt("volumeKeys", v).apply()

    // ---- output (changing these reopens the camera)
    var aspect169: Boolean
        get() = sp.getBoolean("aspect169", false)
        set(v) = sp.edit().putBoolean("aspect169", v).apply()
    var maxResolution: Boolean
        get() = sp.getBoolean("maxResolution", true)
        set(v) = sp.edit().putBoolean("maxResolution", v).apply()
    var heif: Boolean
        get() = sp.getBoolean("heif", false)
        set(v) = sp.edit().putBoolean("heif", v).apply()
    var jpegQuality: Int
        get() = sp.getInt("jpegQuality", 95)
        set(v) = sp.edit().putInt("jpegQuality", v).apply()
    var mirrorFront: Boolean
        get() = sp.getBoolean("mirrorFront", false)
        set(v) = sp.edit().putBoolean("mirrorFront", v).apply()

    fun output() = OutputPrefs(
        aspect169 = aspect169,
        maxResolution = maxResolution,
        heif = heif,
        jpegQuality = jpegQuality,
        mirrorFront = mirrorFront,
        video4k = video4k,
        stabilize = stabilize,
    )

    // ---- last-used shooting settings
    fun loadShot(): ShotSettings {
        val d = ShotSettings()
        return d.copy(
            manualExposure = sp.getBoolean("pro", d.manualExposure),
            isoAuto = sp.getBoolean("isoAuto", d.isoAuto),
            shutterAuto = sp.getBoolean("shutterAuto", d.shutterAuto),
            iso = sp.getInt("iso", d.iso),
            exposureNs = sp.getLong("exposureNs", d.exposureNs),
            evIndex = sp.getInt("ev", d.evIndex),
            wb = enumOr(sp.getString("wb", null), d.wb),
            flash = enumOr(sp.getString("flash", null), d.flash),
            raw = sp.getBoolean("raw", d.raw),
        )
    }

    fun saveShot(s: ShotSettings) {
        sp.edit()
            .putBoolean("pro", s.manualExposure)
            .putBoolean("isoAuto", s.isoAuto)
            .putBoolean("shutterAuto", s.shutterAuto)
            .putInt("iso", s.iso)
            .putLong("exposureNs", s.exposureNs)
            .putInt("ev", s.evIndex)
            .putString("wb", s.wb.name)
            .putString("flash", s.flash.name)
            .putBoolean("raw", s.raw)
            .apply()
    }

    fun resetAll() = sp.edit().clear().apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
