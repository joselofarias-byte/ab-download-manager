#!/usr/bin/env python3
"""Audit the fork network of AB Download Manager for unique work.

The script lists actual GitHub forks of an upstream repository, compares each
fork's default branch against the upstream base branch, filters out merge-only
noise, and writes machine-readable JSON plus a Markdown report.

Authentication:
  GH_TOKEN or GITHUB_TOKEN is recommended. An authenticated token is required
  for a full scan of ~900 forks without hitting GitHub's anonymous rate limit.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

API = "https://api.github.com"
API_VERSION = "2022-11-28"
_lock = threading.Lock()
_rate = {"remaining": None, "reset": None}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--upstream", default="amir1376/ab-download-manager")
    p.add_argument("--base", default="master")
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--max-forks", type=int, default=0,
                   help="0 means scan every fork")
    p.add_argument("--output-dir", default="fork-audit")
    p.add_argument("--min-pushed", default="",
                   help="Optional YYYY-MM-DD cutoff based on fork pushed_at")
    return p.parse_args()


def token() -> str | None:
    return os.getenv("GH_TOKEN") or os.getenv("GITHUB_TOKEN")


def request_json(path: str, attempts: int = 4) -> Any:
    url = path if path.startswith("http") else API + path
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": API_VERSION,
        "User-Agent": "abdm-fork-audit/1.0",
    }
    t = token()
    if t:
        headers["Authorization"] = f"Bearer {t}"

    for attempt in range(attempts):
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=45) as resp:
                with _lock:
                    _rate["remaining"] = resp.headers.get("X-RateLimit-Remaining")
                    _rate["reset"] = resp.headers.get("X-RateLimit-Reset")
                return json.load(resp)
        except urllib.error.HTTPError as e:
            remaining = e.headers.get("X-RateLimit-Remaining")
            reset = e.headers.get("X-RateLimit-Reset")
            retry_after = e.headers.get("Retry-After")
            if e.code in (403, 429) and attempt + 1 < attempts:
                if retry_after:
                    wait = min(120, max(1, int(retry_after)))
                elif remaining == "0" and reset:
                    wait = min(120, max(1, int(reset) - int(time.time()) + 2))
                else:
                    wait = min(30, 2 ** attempt * 3)
                time.sleep(wait)
                continue
            if 500 <= e.code < 600 and attempt + 1 < attempts:
                time.sleep(2 ** attempt)
                continue
            body = e.read().decode("utf-8", "replace")
            raise RuntimeError(f"GitHub API {e.code} for {url}: {body[:500]}") from e
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt + 1 >= attempts:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError(f"Request failed: {url}")


def list_forks(upstream: str) -> list[dict[str, Any]]:
    forks: list[dict[str, Any]] = []
    page = 1
    while True:
        batch = request_json(
            f"/repos/{upstream}/forks?sort=newest&per_page=100&page={page}"
        )
        if not batch:
            break
        forks.extend(batch)
        print(f"listed {len(forks)} forks", file=sys.stderr)
        if len(batch) < 100:
            break
        page += 1
    return forks


MERGE_NOISE_PREFIXES = (
    "merge branch 'amir1376:master'",
    "merge branch \"amir1376:master\"",
    "merge pull request",
    "merge remote-tracking branch",
)


def is_merge_noise(message: str) -> bool:
    m = " ".join(message.lower().split())
    if "amir1376/master" in m and m.startswith("merge"):
        return True
    return m.startswith(MERGE_NOISE_PREFIXES) and "amir1376" in m


def compare_one(upstream: str, base: str, fork: dict[str, Any]) -> dict[str, Any]:
    owner = fork["owner"]["login"]
    branch = fork.get("default_branch") or "master"
    head = urllib.parse.quote(f"{owner}:{branch}", safe=":")
    try:
        cmp = request_json(f"/repos/{upstream}/compare/{base}...{head}")
    except Exception as e:
        return {
            "repo": fork["full_name"],
            "html_url": fork["html_url"],
            "pushed_at": fork.get("pushed_at"),
            "default_branch": branch,
            "error": str(e),
        }

    commits = []
    custom = []
    for c in cmp.get("commits", []):
        msg = c.get("commit", {}).get("message", "").strip()
        item = {
            "sha": c.get("sha"),
            "message": msg,
            "date": c.get("commit", {}).get("author", {}).get("date"),
            "html_url": c.get("html_url"),
            "merge_noise": is_merge_noise(msg),
        }
        commits.append(item)
        if not item["merge_noise"]:
            custom.append(item)

    files = [
        {
            "filename": f.get("filename"),
            "status": f.get("status"),
            "additions": f.get("additions", 0),
            "deletions": f.get("deletions", 0),
            "changes": f.get("changes", 0),
        }
        for f in cmp.get("files", [])
    ]

    return {
        "repo": fork["full_name"],
        "html_url": fork["html_url"],
        "pushed_at": fork.get("pushed_at"),
        "default_branch": branch,
        "status": cmp.get("status"),
        "ahead_by": cmp.get("ahead_by", 0),
        "behind_by": cmp.get("behind_by", 0),
        "total_commits": cmp.get("total_commits", 0),
        "custom_commit_count": len(custom),
        "merge_noise_count": len(commits) - len(custom),
        "custom_commits": custom,
        "all_compared_commits": commits,
        "files": files,
    }


def markdown(results: list[dict[str, Any]], upstream: str, base: str) -> str:
    generated = dt.datetime.now(dt.timezone.utc).isoformat()
    ok = [r for r in results if not r.get("error")]
    interesting = [r for r in ok if r.get("custom_commit_count", 0) > 0]
    noise = [
        r for r in ok
        if r.get("ahead_by", 0) > 0 and r.get("custom_commit_count", 0) == 0
    ]
    errors = [r for r in results if r.get("error")]

    interesting.sort(
        key=lambda r: (
            r.get("custom_commit_count", 0),
            r.get("pushed_at") or "",
        ),
        reverse=True,
    )

    out = [
        "# AB Download Manager fork audit",
        "",
        f"- Generated: {generated}",
        f"- Upstream: `{upstream}`",
        f"- Base: `{base}`",
        f"- Forks scanned: **{len(results)}**",
        f"- Forks with unique non-merge commits: **{len(interesting)}**",
        f"- Merge-only false positives: **{len(noise)}**",
        f"- Compare errors: **{len(errors)}**",
        "",
        "## Forks with unique work",
        "",
        "| Fork | Ahead | Behind | Unique commits | Last push |",
        "|---|---:|---:|---:|---|",
    ]

    for r in interesting:
        out.append(
            f"| [{r['repo']}]({r['html_url']}) | {r.get('ahead_by', 0)} | "
            f"{r.get('behind_by', 0)} | {r.get('custom_commit_count', 0)} | "
            f"{r.get('pushed_at') or ''} |"
        )

    for r in interesting:
        out += ["", f"### {r['repo']}", ""]
        for c in r.get("custom_commits", []):
            first = c["message"].splitlines()[0]
            sha = (c.get("sha") or "")[:8]
            url = c.get("html_url") or r["html_url"]
            out.append(f"- [`{sha}`]({url}) {first}")
        if r.get("files"):
            out.append("")
            out.append("Changed files in compare (first 25):")
            for f in r["files"][:25]:
                out.append(
                    f"- `{f['filename']}` (+{f['additions']}/-{f['deletions']})"
                )

    if noise:
        out += [
            "",
            "## Ahead count caused only by upstream merge noise",
            "",
        ]
        for r in sorted(noise, key=lambda x: x.get("ahead_by", 0), reverse=True):
            out.append(
                f"- `{r['repo']}`: ahead {r.get('ahead_by', 0)}, "
                f"behind {r.get('behind_by', 0)}, unique commits 0"
            )

    if errors:
        out += ["", "## Compare errors", ""]
        for r in errors:
            out.append(f"- `{r['repo']}`: {r['error']}")

    out.append("")
    return "\n".join(out)


def main() -> int:
    args = parse_args()
    cutoff = None
    if args.min_pushed:
        cutoff = dt.datetime.fromisoformat(args.min_pushed).replace(tzinfo=dt.timezone.utc)

    forks = list_forks(args.upstream)
    if cutoff:
        forks = [
            f for f in forks
            if f.get("pushed_at")
            and dt.datetime.fromisoformat(f["pushed_at"].replace("Z", "+00:00")) >= cutoff
        ]
    if args.max_forks:
        forks = forks[: args.max_forks]

    if not token():
        print(
            "WARNING: no GH_TOKEN/GITHUB_TOKEN; a full scan will likely hit "
            "GitHub's anonymous rate limit.",
            file=sys.stderr,
        )

    results: list[dict[str, Any]] = []
    workers = max(1, min(args.workers, 16))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        future_map = {
            pool.submit(compare_one, args.upstream, args.base, f): f
            for f in forks
        }
        done = 0
        for future in concurrent.futures.as_completed(future_map):
            results.append(future.result())
            done += 1
            if done % 25 == 0 or done == len(forks):
                print(
                    f"compared {done}/{len(forks)} "
                    f"(rate remaining={_rate['remaining']})",
                    file=sys.stderr,
                )

    results.sort(key=lambda r: r["repo"].lower())
    outdir = Path(args.output_dir)
    outdir.mkdir(parents=True, exist_ok=True)
    (outdir / "fork-audit.json").write_text(
        json.dumps(results, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    (outdir / "fork-audit.md").write_text(
        markdown(results, args.upstream, args.base),
        encoding="utf-8",
    )

    interesting = sum(1 for r in results if r.get("custom_commit_count", 0) > 0)
    print(f"done: {len(results)} forks, {interesting} with unique work")
    print(outdir / "fork-audit.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
