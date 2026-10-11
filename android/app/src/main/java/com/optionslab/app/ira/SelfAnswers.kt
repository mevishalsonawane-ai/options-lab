package com.optionslab.app.ira

import com.optionslab.ira.SelfTalk

/**
 * Jarvis's answers to [SelfTalk]'s questions (S10-S21), from each system's own host. Words only, apart from opening a sheet
 * (a study page, a replay); "undo everything today" is put to Boss as a request ([ChangeTimelineHost.offerUndoAll]), and
 * an undo that raises live risk is never done by voice. Boss's own trades are his account: refused on a locked phone. A
 * worker thread (the hub calls it off the main thread). Not in IraGoldAlgo.
 */
object SelfAnswers {
    const val LOCKED = "Unlock the phone for that, Boss: it is about your own trades."

    fun answer(a: SelfTalk.Ask, locked: Boolean, context: android.content.Context? = null): String = when (a.kind) {
        SelfTalk.Kind.TIMELINE -> ChangeTimelineHost.say()
        SelfTalk.Kind.UNDO_ALL -> ChangeTimelineHost.offerUndoAll()
        SelfTalk.Kind.FREEZE -> LearningFreeze.answer()
        SelfTalk.Kind.SCORECARD -> MonthlyScorecard.answer()
        SelfTalk.Kind.HABITS -> if (locked) LOCKED else BossTradeLog.sayHabits()
        SelfTalk.Kind.RULES -> if (locked) LOCKED else BossTradeLog.sayRules()
        SelfTalk.Kind.STUDY -> StudyLibrary.answer(context, a.subject.orEmpty())
        SelfTalk.Kind.LESSON -> SelfJobs.answerLesson()
        SelfTalk.Kind.REPLAY -> if (locked) LOCKED else BotReplayStore.answer(a.subject)
    }
}
