package com.starsteps.app.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.starsteps.app.core.Link
import com.starsteps.app.core.Merge
import com.starsteps.app.core.Storage
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The app as a whole: the phone link runs while any of its screens is open. */
class StarStepsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { if (started++ == 0) PhoneLink.start(this@StarStepsApp) }
            override fun onActivityStopped(a: Activity) { if (--started == 0) PhoneLink.stop() }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}

/**
 * The parents' two phones, kept in step over the home Wi-Fi (no server, no account).
 *
 * While the app is open, each phone listens for the other and announces itself on the local
 * network (by a tag of the family, not its name). After each change, and when the app opens,
 * it looks for the other phone and syncs with it ([Link], [Merge]).
 */
object PhoneLink {
    private const val TAG = "StarSteps"
    private const val TYPE = "_starsteps._tcp."
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private var app: Context? = null
    private var server: ServerSocket? = null
    private var registered: NsdManager.RegistrationListener? = null
    private var myName = ""
    @Volatile private var hosting: ((String, (Boolean) -> Unit) -> Unit)? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private val pending = Runnable { app?.let { syncNow(it) } }

    // ------------------------------------------------------------------ the family's link (kept on this phone only)

    private fun prefs(c: Context) = c.getSharedPreferences("starsteps-link", Context.MODE_PRIVATE)
    fun linked(c: Context) = prefs(c).getString("family", null) != null
    private fun familyId(c: Context) = prefs(c).getString("family", null)
    private fun key(c: Context) = prefs(c).getString("key", null)?.let { h -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    fun lastSync(c: Context) = prefs(c).getLong("last", 0L)

    private fun setLink(c: Context, family: String, key: ByteArray) =
        prefs(c).edit().putString("family", family).putString("key", key.joinToString("") { "%02x".format(it) }).apply()

    fun unlink(c: Context) {
        prefs(c).edit().clear().apply()
        restartAnnouncement(c)
    }

    /** Screens that show the family refresh when the other phone's changes come in. */
    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    // ------------------------------------------------------------------ while the app is open

    fun start(c: Context) {
        app = c.applicationContext
        if (server != null) return
        try {
            val s = ServerSocket(0)
            server = s
            pool.execute { acceptLoop(s) }
            restartAnnouncement(c)
            if (linked(c)) main.postDelayed(pending, 1500)
        } catch (e: Exception) {
            Log.w(TAG, "link listener", e)
        }
    }

    fun stop() {
        main.removeCallbacks(pending)
        unannounce()
        try { server?.close() } catch (_: Exception) {}
        server = null
        hosting = null
    }

    /** After a change: sync a moment later (several quick changes go together). */
    fun changed(c: Context) {
        if (!linked(c)) return
        main.removeCallbacks(pending)
        main.postDelayed(pending, 2500)
    }

    private fun nsd(c: Context) = c.getSystemService(NsdManager::class.java)

    private fun unannounce() {
        val l = registered ?: return
        registered = null
        try { app?.let { nsd(it)?.unregisterService(l) } } catch (_: Exception) {}
    }

    /** Tells the network this phone is here: as the family's phone, and/or as ready to be linked. */
    private fun restartAnnouncement(c: Context) {
        unannounce()
        val s = server ?: return
        val linkedTag = familyId(c)?.let { Link.tag(it) }
        if (linkedTag == null && hosting == null) return
        val info = NsdServiceInfo().apply {
            serviceName = "StarSteps-" + Integer.toHexString((Math.random() * 0xFFFFFF).toInt())
            serviceType = TYPE
            port = s.localPort
            linkedTag?.let { setAttribute("fam", it) }
            if (hosting != null) setAttribute("pair", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { myName = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "announce failed $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) = Unit
        }
        registered = l
        try { nsd(c)?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(TAG, "announce", e) }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (e: Exception) { return }
            pool.execute { handle(socket) }
        }
    }

    /** A call from the other phone: linking (if this phone is waiting for it) or syncing. */
    private fun handle(socket: Socket): Unit = socket.use { s -> serve(s) }

    private fun serve(s: Socket) {
        val c = app ?: return
        try {
            s.soTimeout = 150_000
            val first = Link.receive(s)
            when (first.first) {
                'H' -> {
                    val host = hosting ?: return
                    val fid = familyId(c) ?: Link.newFamilyId()
                    val key = key(c) ?: Link.newKey()
                    val ok = Link.hostPairing(s, fid, key, { onMain { Storage.encode(Store.get(c)) } }, confirm = { code ->
                        val latch = CountDownLatch(1)
                        var yes = false
                        main.post { host(code) { yes = it; latch.countDown() } }
                        latch.await(120, TimeUnit.SECONDS) && yes
                    }, first = first)
                    if (ok) {
                        setLink(c, fid, key)
                        hosting = null
                        main.post { restartAnnouncement(c); markSynced(c) }
                    }
                }
                'S' -> {
                    val fid = familyId(c) ?: return
                    val key = key(c) ?: return
                    Link.syncAsServer(s, fid, key, { theirs ->
                        onMain {
                            val merged = Merge.merge(Store.get(c), Storage.decode(theirs))
                            Store.adopt(c, merged)
                            markSynced(c)
                            Storage.encode(merged)
                        }
                    }, first = first)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "link call", e)
        }
    }

    private fun <T> onMain(work: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post { result = runCatching(work); latch.countDown() }
        latch.await(30, TimeUnit.SECONDS)
        return result?.getOrThrow() ?: throw IllegalStateException("timed out")
    }

    private fun markSynced(c: Context) {
        prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
        for (l in listeners.toList()) l()
    }

    // ------------------------------------------------------------------ finding the other phone

    /** Looks on the network for [seconds] and gives every StarSteps phone found (address, port, its tags). */
    private fun discover(c: Context, seconds: Long, found: (NsdServiceInfo) -> Unit, done: () -> Unit) {
        val m = nsd(c) ?: return done()
        val resolving = java.util.ArrayDeque<NsdServiceInfo>()
        var busy = false
        fun next() {
            if (busy) return
            val i = resolving.poll() ?: return
            busy = true
            @Suppress("DEPRECATION")
            m.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, e: Int) { busy = false; main.post { next() } }
                override fun onServiceResolved(info: NsdServiceInfo) { busy = false; found(info); main.post { next() } }
            })
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) = Unit
            override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
            override fun onServiceLost(i: NsdServiceInfo) = Unit
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == myName) return
                main.post { resolving += i; next() }
            }
        }
        try { m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { return done() }
        main.postDelayed({ try { m.stopServiceDiscovery(l) } catch (_: Exception) {}; done() }, seconds * 1000)
    }

    private fun NsdServiceInfo.tag(k: String) = attributes[k]?.let { String(it) }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.connect(): Socket = Socket().apply {
        connect(InetSocketAddress(host, port), 5000)
        soTimeout = 20_000
    }

    /** Syncs with the family's other phone if it is on this network with the app open. */
    fun syncNow(c: Context, done: ((Boolean) -> Unit)? = null) {
        val fid = familyId(c) ?: return done?.invoke(false) ?: Unit
        val key = key(c) ?: return done?.invoke(false) ?: Unit
        val tag = Link.tag(fid)
        var any = false
        discover(c, 6, found = { info ->
            if (info.tag("fam") != tag) return@discover
            pool.execute {
                try {
                    val mine = onMain { Storage.encode(Store.get(c)) }
                    val merged = info.connect().use { s -> Link.syncAsClient(s, fid, key, mine) }
                    onMain {
                        Store.adopt(c, Merge.merge(Store.get(c), Storage.decode(merged)))
                        markSynced(c)
                    }
                    any = true
                } catch (e: Exception) {
                    Log.w(TAG, "sync", e)
                }
            }
        }, done = { main.postDelayed({ done?.invoke(any) }, 1500) })
    }

    // ------------------------------------------------------------------ linking

    /** This phone (with the family) waits to be joined; [onCode] shows the code and asks the parent to confirm. */
    fun host(c: Context, onCode: (String, (Boolean) -> Unit) -> Unit) {
        hosting = onCode
        start(c)
        restartAnnouncement(c)
    }

    fun stopHosting(c: Context) {
        hosting = null
        restartAnnouncement(c)
    }

    /**
     * This phone joins a family: finds the phone waiting to be linked, shows the code
     * ([onCode]), and once the other parent confirmed, [onJoined] decides whether to take it.
     */
    fun join(c: Context, onCode: (String) -> Unit, onJoined: (Link.Joined?) -> Unit, onNone: () -> Unit) {
        var tried = false
        discover(c, 10, found = { info ->
            if (tried || info.tag("pair") != "1") return@discover
            tried = true
            pool.execute {
                val joined = try {
                    info.connect().use { s ->
                        s.soTimeout = 150_000
                        Link.joinPairing(s) { code -> main.post { onCode(code) } }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "join", e)
                    null
                }
                main.post { onJoined(joined) }
            }
        }, done = { if (!tried) onNone() })
    }

    /** Takes the family the other phone sent, and keeps the link. */
    fun accept(c: Context, j: Link.Joined) {
        setLink(c, j.familyId, j.key)
        Store.adopt(c, Storage.decode(j.familyJson).also { it.settings = Store.get(c).settings })
        restartAnnouncement(c)
        markSynced(c)
    }
}
