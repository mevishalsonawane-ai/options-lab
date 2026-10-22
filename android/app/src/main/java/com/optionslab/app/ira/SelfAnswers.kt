package com.optionslab.app.ira

import com.optionslab.ira.SelfTalk

/**
 * Jarvis's answers to [SelfTalk]'s questions (S10-S21), from each system's own host. Words only, apart from opening a sheet
 * (a study page, a replay); "undo everything today" is put to Boss as a request ([ChangeTimelineHost.offerUndoAll]), and
 * an undo that raises live risk is never done by voice. Boss's own trades are his account, and the scorecard's and today's
 * changes' bot results on it too: refused on a locked phone ([SelfTalk.lockedRefusal]). A worker thread (the hub calls it
 * off the main thread). Not in IraGoldAlgo.
 */
object SelfAnswers {
    const val LOCKED = SelfTalk.LOCKED

    fun answer(a: SelfTalk.Ask, locked: Boolean, context: android.content.Context? = null): String =
        SelfTalk.lockedRefusal(a.kind, locked) ?: when (a.kind) {
            SelfTalk.Kind.TIMELINE -> ChangeTimelineHost.say()
            SelfTalk.Kind.UNDO_ALL -> ChangeTimelineHost.offerUndoAll()
            SelfTalk.Kind.FREEZE -> LearningFreeze.answer()
            SelfTalk.Kind.SCORECARD -> MonthlyScorecard.answer()
            SelfTalk.Kind.HABITS -> BossTradeLog.sayHabits()
            SelfTalk.Kind.RULES -> BossTradeLog.sayRules()
            SelfTalk.Kind.STUDY -> StudyLibrary.answer(context, a.subject.orEmpty())
            SelfTalk.Kind.LESSON -> SelfJobs.answerLesson(locked)
            SelfTalk.Kind.REPLAY -> BotReplayStore.answer(a.subject)
            // 22 Oct: the time machine's checks of today's arm entries (memory only; nothing about the account's rupees).
            SelfTalk.Kind.TIME_MACHINE -> com.optionslab.app.data.TimeMachineHost.jarvisLine(com.optionslab.app.data.Market.today())
        }
}
