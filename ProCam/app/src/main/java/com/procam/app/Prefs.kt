package com.procam.app

import android.content.Context

/** Everything ProCam remembers between launches. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("procam", Context.MODE_PRIVATE)

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
