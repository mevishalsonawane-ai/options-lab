# App audit against the books (25 Oct): result

Full table: `research/APP_AUDIT_BOOKS.md`. Everything ran on paper, so no real money was lost.

**Top issues, in plain words**
1. No trade is sized by its risk. One trade can lose more than 2% of the account (Aziz's rule). Your −₹40k / −₹13k account stop is still only logged, and there is no paper copy of it.
2. Every order is a plain market order, and nothing checks the bid-ask spread before buying. The cheaper capped limit that passed research (h14) was never wired in.
3. On 23 Oct the price stream sent heartbeats but no prices for 19 minutes, and the app never reconnected. Stop checks also ran 15 s or more apart six times that week while positions were held.
4. On 23 Oct, R52's NIFTY call and Solo's MIDCPNIFTY put bet opposite ways and both lost. That hit the paper loss limit at 09:49, every NSE paper arm stopped, and R72's 13:45 breaks were refused.
5. Turning an arm off left no line in the change timeline. A moment's failed read could also empty the passed arms' book (every arm off, what they held forgotten).
6. US-night silver's short was bought back at 14:39, nine hours early. The NSE loss stop was wrongly read as an MCX stop while Zerodha reads were failing.
7. "No fills measured yet" every day: paper slippage was never shown, and resting stops were never measured.

**Fixed (tested, paper or risk-lowering only):** problems 3 (it now reconnects after 20 s without prices), 5, 6 and 7. The loss
stop's message now lists each position and flags opposite bets. The paper loss limit's row says it sells what the bots hold,
and loosening it asks your PIN. Jarvis now says which paper limits are actually enforced.

**Needs your decision:** turn the account stop on (with a paper copy); a 2% per-trade risk cap; a spread check and the capped
limit (both start in shadow); faster stop checks while the stream is quiet; whether to block opposite bets between arms; a
weekly loss stop; logging IV at entry before any IV filter.
