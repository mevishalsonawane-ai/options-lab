package com.optionslab.ira

/**
 * Nothing secret is ever kept (the owner's rule, 2026-10-02): before a question is stored, shown, saved or given to the
 * model, anything that looks like a secret is replaced with [HIDDEN] - card numbers, long account-like digit runs,
 * Aadhaar and PAN numbers, UPI ids, e-mail addresses, phone numbers, and whatever follows "password", "PIN", "OTP",
 * "CVV" and the like. Market figures (24000, 52,140.25, 2 lots) are left alone. Pure.
 */
object Secrets {
    const val HIDDEN = "[hidden]"

    private val RULES = listOf(
        // "my password is hunter2", "pin 4321", "otp: 889911", "cvv is 123", "passcode = x"
        Regex("(?i)\\b(password|passwd|passcode|pass word|pin|mpin|upi pin|otp|one time password|cvv|cvc|secret|api key|api secret|totp|2fa code|login code)\\b(\\s*(is|was|:|=|-)?\\s*)(\\S+(\\s+\\d{1,4}){0,3})"),
        // Card numbers: 13-19 digits, with spaces or dashes in between.
        Regex("\\b(?:\\d[ -]?){12,18}\\d\\b"),
        // Aadhaar (12 digits in fours), any other run of 9 or more digits (account numbers, phone numbers with code).
        Regex("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b"),
        Regex("\\b\\d{9,}\\b"),
        // Indian mobile numbers (10 digits starting 6-9), with or without +91.
        Regex("(?:\\+?91[ -]?)?\\b[6-9]\\d{4}[ -]?\\d{5}\\b"),
        // PAN (ABCDE1234F), e-mail addresses and UPI ids (name@bank).
        Regex("(?i)\\b[a-z]{5}\\d{4}[a-z]\\b"),
        Regex("(?i)\\b[\\w.+-]+@[\\w-]+(\\.[\\w-]+)*\\b"),
    )

    fun redact(text: String): String {
        var t = text
        val first = RULES.first()
        t = first.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + HIDDEN }
        for (r in RULES.drop(1)) t = r.replace(t, HIDDEN)
        return t
    }

    fun hasSecret(text: String) = redact(text) != text
}
