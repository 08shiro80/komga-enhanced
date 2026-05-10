#!/usr/bin/env bash
# Wait for the queued RefreshSeriesMetadata to land for Lookism after a clear,
# then prove the refresh hook ran auto-match + the link was populated.
for i in $(seq 1 80); do
  if docker logs komga 2>&1 | grep "Auto-match: applied plugin='anilist-metadata' id=86848" | grep -v 'iter=' >/dev/null \
     && docker logs komga 2>&1 | grep -q 'Apply metadata for series: Lookism'; then
    echo "FOUND iter=$i $(date)"
    break
  fi
  sleep 3
done
echo "=== Auto-match lines from refresh path ==="
docker logs komga 2>&1 | grep -E 'Auto-match' | tail -10
echo "=== Mylar parse ==="
docker logs komga 2>&1 | grep -E 'MylarSeriesProvider.*Lookism|Apply metadata for series: Lookism' | tail -8
