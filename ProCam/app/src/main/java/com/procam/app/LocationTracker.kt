package com.procam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** Keeps a recent location fix for geotagging photos. */
class LocationTracker(context: Context, private val onLocation: (Location?) -> Unit) : LocationListener {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private var running = false
    private var best: Location? = null

    fun hasPermission() =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission() || manager == null) return
        running = true
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                if (!manager.allProviders.contains(provider)) continue
                manager.getLastKnownLocation(provider)?.let { consider(it) }
                manager.requestLocationUpdates(provider, 10_000L, 10f, this, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            manager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        onLocation(null)
    }

    private fun consider(l: Location) {
        val current = best
        val newer = current == null || l.time > current.time + 30_000
        val moreAccurate = current == null || l.accuracy <= current.accuracy
        if (newer || moreAccurate) {
            best = l
            onLocation(l)
        }
    }

    override fun onLocationChanged(location: Location) = consider(location)

    // Required on Android 10, where these aren't default methods yet.
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
}
