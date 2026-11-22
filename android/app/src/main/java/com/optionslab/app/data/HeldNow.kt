package com.optionslab.app.data

/**
 * 20 Oct, enhancement round 1 (idea 7): the books' memory hints gathered in ONE place for [com.optionslab.ira.Held] - the shared
 * "is anything held?" answer the watch's pulse, its 15-second stop check, the arms' waits and the smart scheduler all use
 * (four lists before, each a little different). Memory only: never a lock, a vault read or the network (a hung pass may hold a
 * lock; the pulse must still answer). Anything unreadable counts as held.
 */
object HeldNow {
    /** What each book says now, from memory. */
    // 19 Nov 2026 (audit batch 2): [mcx] false leaves out Zerodha's MCX book (its calendar may be a file read): for the main thread.
    fun sources(mcx: Boolean = true): com.optionslab.ira.Held.Sources {
        fun q(f: () -> Boolean): Boolean = runCatching(f).getOrDefault(true)
        val paper = runCatching {
            Paper.stateIfLoaded()?.let { st -> st.positions.any { it.quantity != 0 } || st.orders.any { it.status == "open" || it.status == "trigger pending" } }
        }.getOrDefault(null)
        return com.optionslab.ira.Held.Sources(
            cards = q { com.optionslab.app.work.PositionCards.anyOpen },
            paperBook = paper,
            protections = runCatching { Protections.activeHint }.getOrNull(),
            orb = q { OrbArms.holdingHint },
            orbLive = q { OrbArms.liveHint },
            pine = q { PineAuto.holding() },
            strategies = q { Strategies.runningHint },
            armLive = q { ArmLive.holdingHint },
            armLiveRecovering = q { ArmLive.recoverHint },
            passed = q { PassedArmsHost.holding() },
            hero = q { HeroTicketArm.holding() },
            ft3 = q { Ft3Arm.holding() },
            signal = q { NtfyArm.holding() },
            flow = q { FlowArm.holding() },
            s5 = q { S5Arm.holding() },
            cisd = q { CisdArm.holding() },
            night = q { NightArm.view.value.open.isNotEmpty() },
            vixDiv = q { VixDivArm.view.value.open.isNotEmpty() },
            mcxArms = q { McxPaperArms.view.value.open.isNotEmpty() },
            // Zerodha's MCX book only while MCX trades (as before): its "not read yet today" counts as held, which outside MCX's
            // hours would keep the processor awake all night for nothing.
            mcxLive = mcx && q { McxMarket.isOpen() && McxMarket.heldQuick() },
            // 19 Nov 2026 (audit batch 2): Zerodha's own positions at the reconciler's last good read ([Reconciler], memory only;
            // it lapses 10 minutes after the last read), so the watch never sleeps while Zerodha holds anything.
            broker = Reconciler.heldHint(),
        )
    }

    /**
     * Anything held anywhere (memory only; unknown counts as held). [mcxLive]: Zerodha's MCX book counts too (the watch's pulse,
     * as before). Review: the other workers leave it out, as they did - its "not read yet today" counts as held, which kept the NSE
     * loop on its 15-second path all day for nothing on a paper-only day.
     */
    fun any(mcxLive: Boolean = false): Boolean = runCatching {
        // 22 Nov 2026 (B6 review): MCX's part is not even read when it is left out (its calendar, its book's hint).
        com.optionslab.ira.Held.any(sources(mcx = mcxLive))
    }.getOrDefault(true)
}
