package com.optionslab.app.data

import kotlinx.coroutines.withTimeoutOrNull

/**
 * A check of the real Zerodha connection that can never trade: every step only READS
 * (session, funds, positions, orders, holdings, GTTs, a quote, the contract list) and
 * the static-IP check asks where an order would come from without sending one.
 *
 * Results carry counts and pass / fail, never amounts, tokens or order ids, so the card
 * can be shown or screenshotted safely.
 */
object LiveSelfTest {
    data class Step(val name: String, val ok: Boolean, val detail: String, val ms: Long)

    /** Runs every step in turn (each at most 20 s) and reports each one; a failed step does not stop the rest. */
    suspend fun run(onStep: (Step) -> Unit = {}): List<Step> {
        val out = ArrayList<Step>()
        suspend fun step(name: String, block: suspend () -> String) {
            val t0 = System.currentTimeMillis()
            val s = try {
                val detail = withTimeoutOrNull(20_000) { block() }
                if (detail == null) Step(name, false, "no answer in 20 s", System.currentTimeMillis() - t0)
                else Step(name, true, detail, System.currentTimeMillis() - t0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Step(name, false, reason(e), System.currentTimeMillis() - t0)
            }
            out += s; onStep(s)
        }
        step("Session") {
            if (!Broker.configured) error("Zerodha is not set up on this phone")
            if (!Broker.loggedIn) error("not logged in today: log in first")
            "logged in"
        }
        if (out.last().ok) {
            step("Funds") { Broker.funds(); "read" }
            step("Positions") { "${Broker.positionBook().net.size} open or closed today" }
            step("Order book") { "${Broker.orders().size} orders today" }
            step("Holdings") { "${Broker.holdings().size} holdings" }
            step("GTT triggers") { "${Broker.gtts().size} triggers" }
            step("Quote (NIFTY 50)") { if (Broker.indexQuote("NIFTY") != null) "price received" else error("no price returned") }
            step("Contract list") { "${Broker.instruments().size} contracts" }
        }
        step("Static IP for orders") {
            if (StaticIp.registered == null) error("no static IP registered in the app (More → Zerodha → Static IP)")
            StaticIp.entryBlock()?.let { error(it) }
            "orders would leave from the registered IP"
        }
        return out
    }

    /** Zerodha's own error text is already free of keys and URLs; anything else is named by its type only. */
    private fun reason(e: Exception): String = when (e) {
        is Broker.KiteError, is Broker.NotLoggedIn, is IllegalStateException -> e.message ?: e.javaClass.simpleName
        is java.net.UnknownHostException -> "no internet connection"
        is java.net.SocketTimeoutException -> "Zerodha did not answer in time"
        else -> e.javaClass.simpleName
    }
}
