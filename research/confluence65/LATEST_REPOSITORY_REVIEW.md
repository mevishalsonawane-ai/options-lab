# Concurrent repository update — 14 November 2026

During the final publishing check, main advanced from adbc8d53 to e0b8a526
through two R172 commits. This experiment remains pinned to its original
experimental parent; it did not merge or overwrite the newer main changes.
The new R172 summary and detailed report were read at e0b8a526. R172 was not
independently rerun in this batch and is not part of its40setting count.

R172 tests a whole-position +Rs2000 target/-Rs1000 stop on Liquidity, R72 and R51
at1–3lots, with and without the existing picker, on20Apr–19Oct2026. It does not
establish65% wins. Its one-lot tables show roughly37–42% wins for the changed
rules; several original/picker baselines are profitable at lower win rates.
Those separate records must not be mixed with this batch's NIFTY-only account.

A documentation inconsistency matters: R172's summary says every arm loses
with the new rule, with or without the picker. Its own detailed one-lot table
shows R72 B+2k1k at557trades,42%win and +Rs25485, with worstmonth -Rs34164.
Thus the universal-loss headline is contradicted by its table. The table does
not pass the65% objective or the -Rs13000 monthly budget either. This is a source
consistency finding, not a claim that those rupee results were reverified here.

Reference at the inspected commit:
https://github.com/mevishalsonawane-ai/options-lab/blob/e0b8a526c32add5ac927c75e8b4778b543880ada/research/hunt/r172_2k1k_6months/results/report.md

Our conclusion is specific: the registered confluence batch found no qualifying
65%-win profitable strategy. It does not prove all possible strategies or all
existing repository baselines lose. Unknown future main changes are not reviewed.
