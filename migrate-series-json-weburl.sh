#!/usr/bin/env bash
# One-shot migration for legacy series.json files written by the AniList apply
# flow before the web_url patch. For every series.json under the given root
# whose `comicid` is all-digits (AniList) and which has no `web_url`, add
# web_url = "https://anilist.co/manga/<comicid>" so MylarSeriesProvider produces
# a correct AniList WebLink (which the scrobbler then auto-detects).
#
# UUID-style comicids are left alone (already work as MangaDex links).
# Files are rewritten via tmp+atomic-mv so a crash mid-write can't corrupt them.
#
# Usage:  ./migrate-series-json-weburl.sh /mnt/drive2/suwayomi/downloads/mangas
#         (defaults to that path if no arg given)
#
# Dry-run by default — set APPLY=1 to actually write.
set -euo pipefail

ROOT="${1:-/mnt/drive2/suwayomi/downloads/mangas}"
APPLY="${APPLY:-0}"

if [[ ! -d "$ROOT" ]]; then
  echo "ERROR: $ROOT is not a directory" >&2; exit 1
fi
command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }

scanned=0; eligible=0; written=0; skipped_uuid=0; skipped_haveurl=0; skipped_other=0

while IFS= read -r -d '' f; do
  scanned=$((scanned+1))
  comicid="$(jq -r '.metadata.comicid // ""' "$f" 2>/dev/null || true)"
  weburl="$(jq -r '.metadata.web_url // ""' "$f" 2>/dev/null || true)"

  if [[ -n "$weburl" ]]; then
    skipped_haveurl=$((skipped_haveurl+1)); continue
  fi
  if [[ -z "$comicid" ]]; then
    skipped_other=$((skipped_other+1)); continue
  fi
  # UUID? (MangaDex) — leave alone, MylarSeriesProvider already handles this.
  if [[ "$comicid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
    skipped_uuid=$((skipped_uuid+1)); continue
  fi
  # Numeric AniList id?
  if [[ "$comicid" =~ ^[0-9]+$ ]]; then
    eligible=$((eligible+1))
    new_url="https://anilist.co/manga/${comicid}"
    echo "  ${f}  +web_url=${new_url}"
    if [[ "$APPLY" == "1" ]]; then
      tmp="$(mktemp "${f}.XXXXXX")"
      jq --arg u "$new_url" '.metadata.web_url = $u' "$f" > "$tmp"
      mv -f "$tmp" "$f"
      written=$((written+1))
    fi
  else
    skipped_other=$((skipped_other+1))
  fi
done < <(find "$ROOT" -type f -name 'series.json' -print0)

echo
echo "scanned=$scanned eligible=$eligible written=$written skipped_uuid=$skipped_uuid skipped_haveurl=$skipped_haveurl skipped_other=$skipped_other"
[[ "$APPLY" != "1" ]] && echo "(dry-run — re-run with APPLY=1 to write changes)"
