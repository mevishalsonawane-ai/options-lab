package com.optionslab.engine.orb

/**
 * "R51 picker (shadow)" (6 Nov 2026, Boss: "Do whatever you recommend", after "What about r51?"): research R151's logistic trade
 * picker for R51 Donchian 20 (research/HUNT_R151_DONCHIAN_WINRATE60.md, Amendment E; model R51-20-3.0-M2, threshold [THRESHOLD]
 * fixed before its read). The same 28 features as R72's ([R72Picker.features], R51's breaks with no breadth count) and its own
 * frozen numbers. It FAILED its gate by 0.1 point (Apr - Oct 2026: 693 trades won 49.9%, the bar was 50%), so it is a SHADOW LOG
 * ONLY (CLAUDE.md: a failed idea stays a note): each R51 break is scored and logged; nothing is skipped or changed, paper or live.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object R51Picker {
    const val LABEL = "R51 picker (shadow)"
    /** R51's shadow log: W20 and this picker, one line per bar, under one switch. */
    const val LINE = "R51 shadow (W20 + picker)"
    const val RESEARCH_ID = "R151 Amendment E (R51-20-3.0-M2)"
    const val THRESHOLD = 0.4469
    const val STATUS = "failed gates; a shadow log only"
    const val RECORD = "Research R151 Amendment E, Apr - Oct 2026 (2nd look): 693 trades, 49.9% won (gate 50%: failed by 0.1 point), " +
        "+₹1,59,040 vs R51's +₹1,34,743 (1,670 trades); per trade ₹229 vs ₹81; worst month −₹15.6k vs −₹54.6k; drawdown ₹33.6k vs ₹81.7k; " +
        "better than random skips (p 0.0005)"

    /** R51-20-3.0-M2 (results/r51_picker_model.json). */
    val MODEL = R72Picker.Model(
        median = doubleArrayOf(110.0, 1.0, 0.002750256085119085, 0.0016584717132456794, 0.5950823525259861,
            0.0006235569201019642, 0.0018140703725043061, 0.00042996824235633734, 0.0022932343734653,
            1.5484683078596269, 0.0004952500401295226, 1.0, 0.002939365439127206, 0.00198989378443154,
            0.0017064792555906427, 7.0, 0.0, 0.002299381682629442, 13.48, 0.0048719729608432995, 0.6, 0.368,
            0.0005884139397160926, 0.0034414993080444933, 5.0, 2.0, 0.0, 0.0),
        mean = doubleArrayOf(131.70270912547528, 0.02994296577946768, 0.002751658857076507, 0.0018594688135568993,
            0.656792543043151, 0.0008931145576062426, 0.002023967682609456, 0.0005965594510652359,
            0.0031613153109191073, 2.126859977501985, 0.0012339955964888404, 2.3438688212927756,
            0.0031159991172357454, 0.009924684660102812, 0.00429738327316864, 8.835551330798479,
            0.32533269961977185, 0.00655329913596537, 13.871582699619825, 0.0083772211352584,
            0.5251901140684438, 0.4281511406844059, 0.000259417029564917, 0.009252288964311456,
            4.383792775665399, 2.027804182509506, 0.0, 0.9757604562737643),
        scale = doubleArrayOf(112.23942688304088, 0.999551608872853, 0.006775121478698302, 0.0036243709140234724,
            0.32451539998436557, 0.006282663461052236, 0.0018460690278880678, 0.0005681646964363625,
            0.003146031605177922, 1.8086859407037625, 0.0028488473214983503, 2.790422827626093,
            0.020468587543494867, 0.017549878546726065, 0.009687332748113356, 7.589864062228374,
            0.4684990226007579, 0.013044916350218134, 3.092716047763858, 0.05083536979777299,
            0.422211791114678, 0.3237958153037261, 0.0068086053053971985, 0.05700625054165717,
            1.2700085576584375, 1.4803647453063227, 1.0, 0.9997061790946028),
        coef = doubleArrayOf(-0.07306196987347421, 0.029875123544175188, -0.06680356203574579, -0.07391518328522292,
            -0.019411695792757977, -0.02774584076861418, 0.09316238735427611, -0.13089592556391177,
            0.13389999545525638, 0.030870028245684616, -0.014960201374536181, -0.1220098558204397,
            0.004496430123211135, 0.05410331162149973, -0.006337809254693818, 0.003730800166587646,
            -0.038191167622742245, 0.08940955068170936, 0.08712609295081211, -0.09738321171069746,
            -0.0769212923257875, -0.14481200849684656, 0.05527991175331855, 0.1262466815325542,
            -0.006597761925279205, 0.0640654910618526, 0.0, 0.030210415940393455),
        intercept = -0.22578562407804809,
        threshold = THRESHOLD)

    fun score(f: DoubleArray): Double = MODEL.score(f)
    fun takes(score: Double): Boolean = MODEL.takes(score)

    /** One index's part of R51's shadow line for a bar: the W20 label and the picker's score (null: it could not be scored). */
    fun part(und: String, side: Int, w20Skips: Boolean, score: Double?): String =
        "$und ${if (side > 0) "call" else "put"}: W20 ${if (w20Skips) "would skip" else "would keep"}" +
            (score?.let { ", picker ${"%.3f".format(java.util.Locale.ENGLISH, it)} ${if (takes(it)) "would take" else "would skip"}" }
                ?: ", picker could not score it")
}
