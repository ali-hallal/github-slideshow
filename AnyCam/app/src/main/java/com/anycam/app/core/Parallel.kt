package com.anycam.app.core

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Splits row-by-row work over the processor's cores. */
object Parallel {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "anycam-work").apply { isDaemon = true } }

    /** Runs [body] for every row in 0 until [count], on several threads; returns when all are done. */
    fun rows(count: Int, body: (Int) -> Unit) {
        if (count <= 0) return
        val next = AtomicInteger(0)
        val jobs = (0 until minOf(threads, count)).map {
            pool.submit {
                while (true) {
                    val r = next.getAndIncrement()
                    if (r >= count) break
                    body(r)
                }
            }
        }
        jobs.forEach { it.get() }
    }
}
