# R117 result (27 Oct 2026): a profit lock does not make the FT3 / Hero tickets pay; 0 of 12 pass; nothing changes

1. 27 Oct could not be replayed: the lake ends 19 Oct, the Dhan token is expired, the phone's recording is sealed (`r117day.py` is ready for it).
2. FT3 never bought the 22700 PE. In 2021-26, 91% of OTM options at Rs 6-10 fail FT3's distance test, and 87% of the near ones that are puts fail its IV test (skew).
3. Even bought at Rs 10, FT3's hold-to-15:05 would have lost about Rs 5,050. Hero was switched off.
4. On FT3's 478 tickets (2021-26), holding to 15:05 makes +Rs 1,805 a ticket. No lock beats it in design, walk-forward AND final.
5. A lock wins on round trips like 27 Oct (52 tickets, hold −Rs 2.0 lakh) but sells the 69 late runners early (hold +Rs 26.8 lakh).
6. Closest miss: the step lock (L3). It lost in 2024-25 (+679 vs +1,568), its twin p is 0.95, and it is below 0 in the final without its 3 best days.
7. Hero (847 tickets): every exit loses. Today's 25% trail from the fill loses least (−Rs 61 a ticket); locks without a stop lose −Rs 178 to −226.
8. Gates: BH over 9.3M logged tests plus random twins (2,000 each). Signal source: option-price rules (not the default), labelled.
9. FT3 stays frozen. No lock earns a paper variant. Nothing live. Write-up: `research/HUNT_R117_ZERO_HERO_LOCK.md`.
10. Code and tests: `research/hunt/r117_zero_hero_lock/` (34 tests, look-ahead guard). Entries: `research/experiments/entries/r117-*.yaml`.
