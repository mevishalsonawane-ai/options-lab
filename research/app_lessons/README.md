# App lessons (the phone's findings, back in the research)

13 Oct 2026 (Boss: "Jarvis's knowledge should be shared with the agents, and the findings from the research should be shared
with Jarvis"). Each night the phone writes a **lessons bundle** (`recordings/YYYY-MM-DD/lessons_bundle.json`, ira-core
`LessonsBundle`): Jarvis's lessons with their evidence counts, a summary of the findings log, the strategy lab's ideas (tried
and skipped) and variants, the order review's recurring flaws, the trade manager's vote outcomes and the shared knowledge's
shadow counts. Boss's money figures travel in it only encrypted with the app's Vault (`money_enc`), and are decrypted only in
his PIN-confirmed **Export recordings** zip.

```
python research/knowledge/import_app_lessons.py iraalgo-recordings-2026-10-13.zip   # every day's bundle in the export
python research/experiments/validate.py                                             # the app-lab-* entries it adds
python research/knowledge/build_pack.py                                             # the next APK carries them back
python research/knowledge/test_import_app_lessons.py                                # the no-money rule, tested
```

## The rule

**Never commit raw personal P&L.** The importer keeps only counts (n, hits, tried, passed, days), rates (hits / n) and unitless
effects (the rate minus a half, the sign), plus names, tags and dates. Every rupee field (`money`, `money_enc`, `rs`, `net`,
`pnl`, `rupees`, `charges`, `slippage`, ...) and any text quoting rupees is dropped, and the importer refuses to write a summary
that still holds one (`money_free`). `test_import_app_lessons.py` checks it with planted figures.

## What is here

- `YYYY-MM-DD.json` - one imported day (kind `iraalgo-app-lessons`); `build_pack.py` turns its strong and building lessons into
  `app_lesson` facts and shared-knowledge items (source `app`), so a phone lesson that disagrees with a research verdict is said
  as a conflict with both sample sizes, never picked over it.
- `YYYY-MM-DD.md` - the same day as a short page (the `doc` of the experiment entries below).
- `research/experiments/entries/app-lab-*.yaml` - one entry per strategy-lab idea family the phone tested, so the
  multiple-testing count includes the phone's own tries (refreshed on each import).

Until G1 (the phone's own uploader) ships, the export is taken by hand; G1 will upload `lessons_bundle.json` as stored
(encrypted), the same file.
