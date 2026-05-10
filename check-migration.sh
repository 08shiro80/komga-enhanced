#!/usr/bin/env bash
for f in \
  '/mnt/drive2/suwayomi/downloads/mangas/Atsumaru (EN)/One Piece/series.json' \
  '/mnt/drive2/suwayomi/downloads/mangas/Mangago (EN)/One Piece/series.json' \
  '/mnt/drive2/suwayomi/downloads/mangas/Atsumaru (EN)/Lookism/series.json'; do
  echo "$f"
  jq '{name:.metadata.name, web_url:.metadata.web_url, comicid:.metadata.comicid, status:.metadata.status}' "$f"
  echo
done
