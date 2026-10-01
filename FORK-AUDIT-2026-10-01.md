# Fork mining — AB Download Manager — 2026-10-01

## Goal

Find genuinely useful work hidden in the AB Download Manager fork network without mistaking stale forks or merge-only history for improvements.

Upstream baseline: `amir1376/ab-download-manager:master`.

Our fork `joselofarias-byte/ab-download-manager:master` was verified identical to upstream at commit `390436747934bfe67fc94e47ccbc02c9497efd4c` when this audit started.

## What was checked

- GitHub search surfaced more than 300 forks with activity during 2026.
- Candidate forks with unusual repository size, recent pushes, feature branches, or apparent ahead counts were compared against upstream.
- Ahead counts were inspected for false positives caused only by repeated merges from `amir1376:master`.

## High-value findings

### farizakbar11/ab-download-manager

Compare against current upstream: **16 commits ahead / 5 behind**.

Unique work includes:

- IDM-style YouTube Video Catcher and browser overlay.
- yt-dlp download engine integration with live progress.
- ffmpeg muxing and open-file/open-folder actions.
- 4K format selection and native queue integration.
- Multi-connection fragments.
- Configurable thread count and retry behavior.
- Google Drive filename recovery.
- Faster live speed/progress calculation.
- Larger socket/assembly buffers and OkHttp connection-pool tuning.
- Configurable speed refresh interval from 50 ms to 5000 ms, including Android.

One commit rebrands the app to **4get Download Manager**. That branding work should not be imported into our fork.

Most interesting commits for selective porting:

- `2c0cdc8` — configurable speed refresh interval.
- `7a5373a` — downloader buffers and OkHttp connection pool.
- `f1e8097` — Google Drive filename + real-time speed calculation.
- `2a44e15` and related commits — yt-dlp/YouTube catcher stack, mainly desktop/browser integration.

### hamidrg20001379/ab-download-manager

Compare against current upstream: **1 unique commit ahead / 57 behind**.

Commit `0dc6477` adds a **Page Watcher** feature with:

- settings UI;
- watched-page model;
- background page watcher service;
- desktop integration.

This is interesting as a separate optional feature, but it is based on an older upstream snapshot and should be ported rather than merged wholesale.

### sara-dev12/ab-download-manager

Feature branch `add/twilight-theme` contains at least:

- `dd0ac57` — Twilight theme.
- `e704160` — Android notification when a download completes.

The Android completion notification is more functionally relevant than the theme and deserves comparison against current upstream before porting.

### Diselectron-Developer/ab-download-manager

Feature branch `fix/initial-maximized-home-window` contains:

- `739daa9` — fix for initially-maximized desktop window behavior.

Desktop-only, small and potentially portable.

## False-positive example

`xiaoguangliu049/ab-download-manager` reports **17 commits ahead / 57 behind**, but all 17 ahead commits inspected are merges of `amir1376:master`; there is no unique feature work in that ahead count.

This is why the audit tool filters merge-only noise instead of sorting forks only by `ahead_by`.

## Other forks checked

A broad sample of apparently large/recent forks was compared and found to be only behind upstream, including examples such as:

- `Coolboyrajat/ab-download-manager`
- `BehroozRezvani/ab-download-manager`
- `sam-reza/ab-download-manager`
- `CloudEngineHub/ab-download-manager`
- `Mu-L/ab-download-manager`
- `jhopan/ab-download-manager`
- `bulone/ab-download-manager`
- `Ever4engel/ab-download-manager`
- `fossFriend/ab-download-manager`
- `doctor-martin-sowa/ab-download-manager`

## Automation added in this branch

`tools/fork_audit.py` performs the full fork-network scan through GitHub's REST API.

It:

1. lists actual forks from the upstream fork network;
2. compares each fork's default branch with upstream `master`;
3. records ahead/behind counts;
4. separates likely unique commits from merge-only upstream-sync noise;
5. records changed files;
6. emits `fork-audit/fork-audit.json` and `fork-audit/fork-audit.md`.

For the full ~900-fork network it should be run authenticated with `GH_TOKEN` or `GITHUB_TOKEN`.

## Next engineering pass

Do **not** merge any whole divergent fork.

Use current upstream as the base and selectively port candidate commits in isolated branches, beginning with the Android/shared downloader improvements from `farizakbar11`, then validate build/tests before considering the larger YouTube stack or Page Watcher.
