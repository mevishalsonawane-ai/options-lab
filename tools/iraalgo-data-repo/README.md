# IraAlgo data

Private and **data only** (Boss, 13 Oct 2026). The app's code lives in `options-lab`; nothing here builds, signs or
publishes an app.

## What is here

- **Releases `live-YYYY-MM`**: the phone's recorded days, one `live-YYYY-MM-DD.irlive` a day.
- **Release `phone-backup`**: the phone's backups, `phone-backup-YYYY-MM-DD-HHMMSS.irabk`. The phone keeps the last 30 daily
  backups plus one a month before them.
- **`CATALOG.json`**: every uploaded file that passed the nightly check (day, size, sha256, when checked). The Data
  Brain reads its live days from here.
- **`verify_uploads.py`** and **`.github/workflows/verify-uploads.yml`**: the nightly check (03:22 IST). It loads the last
  catalogue first and downloads only new or replaced files. It checks each file's structure without decrypting it:
  - the header;
  - the chunk layout: every chunk holds at least its 16-byte tag, and the file is not cut short;
  - the size against GitHub's listing;
  - the name and month.

  It needs no passphrase and has none: the AES-GCM tags are checked only where the passphrase is typed, on a PC
  (`decrypt_live.py` in options-lab) or on the phone. A bad file turns the run red. With the `NTFY_TOPIC` secret set, the
  phone also gets a notification.
- `tests/`: the checker's tests. The workflow runs them before every check.

Everything uploaded is sealed on the phone with Boss's backup passphrase (AES-256-GCM, a key from PBKDF2-SHA256). GitHub
only ever holds noise.

## Rules for this repository

- **Data only.** Add no code beyond the checker, and never an app build, a signing key or a release of the app.
- **The phone's token** is a fine-grained token for this repository **only**, with:
  - Contents: Read and write;
  - Metadata: Read (GitHub adds it);
  - a 90-day expiry.

  Never give it Workflows, Actions, Secrets or Administration.

  Without the Workflows permission it cannot change the workflow file, and the workflow pins `verify_uploads.py` and its
  tests by sha256. So a stolen token cannot change the code the nightly job runs. It can still:
  - replace or delete uploads;
  - edit plain files such as `CATALOG.json`. After a leak, check the commits for anything not made by
    `github-actions[bot]`.

  The uploads are sealed: it cannot read them. MAINTENANCE.md in options-lab has the steps.
- **Secrets:** at most `NTFY_TOPIC`. Never the backup passphrase, and never a token for options-lab.
- Never force-push. The nightly job commits only `CATALOG.json`.
- Changing `verify_uploads.py` or its tests means updating their pinned hashes in the workflow in the same commit.
- Settings → Actions → General → Workflow permissions: **Read and write permissions**, so the job can commit
  `CATALOG.json`. Scheduled jobs run from the default branch.

## Opening a recorded day on a PC

Download the `.irlive` file from its release, then, in options-lab:

```
pip install cryptography
python research/data/decrypt_live.py live-2026-10-13.irlive --extract days/
```

It asks for the backup passphrase. It checks every tag and every file against the day's manifest.
