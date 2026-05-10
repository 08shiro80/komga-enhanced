#!/usr/bin/env bash
# Wait until the queued series.json refresh applies for Lookism, then dump
# the relevant log lines + report final link state.
for i in $(seq 1 80); do
  count=$(docker logs komga 2>&1 | grep -c "Auto-match: applied plugin='anilist-metadata' id=86848")
  applies=$(docker logs komga 2>&1 | grep -c "Apply metadata for series: Lookism")
  if [ "$applies" -ge 1 ]; then
    echo "applies=$applies count=$count iter=$i $(date)"
    break
  fi
  sleep 3
done
echo "=== Auto-match log lines ==="
docker logs komga 2>&1 | grep -E 'Auto-match|automatch' | tail -10
echo "=== Mylar parse + apply ==="
docker logs komga 2>&1 | grep -E 'MylarSeriesProvider|Apply metadata for series: Lookism' | tail -10
