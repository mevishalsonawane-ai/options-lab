package com.optionslab.app.ira

import com.optionslab.ira.SaidAbout

/**
 * "What did I say about X?" ([SaidAbout], usefulness round 14): Boss's own words the app already keeps - the notes he
 * asked Jarvis to remember ([IraTools.memory]), his notes with trades ([IraJournal.notes]) and his journal answers
 * ([IraDayJournal.allAnswers]) - searched and read back as he said them, with where and when. Read back only: nothing is
 * taken as a rule, an order or a setting, and nothing acts. His words, so the hub asks for it only on an unlocked phone.
 */
internal object IraSaidAbout {
    fun answer(asked: SaidAbout.Asked): String {
        val all = ArrayList<SaidAbout.Said>()
        runCatching { IraTools.memory() }.getOrDefault(emptyList()).forEach { all += SaidAbout.Said(SaidAbout.Source.NOTE, it.day, it.text) }
        runCatching { IraJournal.notes() }.getOrDefault(emptyList()).forEach { (t, n) ->
            all += SaidAbout.Said(SaidAbout.Source.TRADE_NOTE, t.toLocalDate(), n, at = t)
        }
        runCatching { IraDayJournal.allAnswers() }.getOrDefault(emptyList()).forEach { (d, a) ->
            all += SaidAbout.Said(SaidAbout.Source.JOURNAL, d, a.answer, at = a.at, question = a.question)
        }
        return SaidAbout.say(asked, all, com.optionslab.app.data.Market.today())
    }
}
