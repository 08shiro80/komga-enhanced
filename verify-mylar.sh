#!/usr/bin/env bash
# Wait up to 4 minutes for the Lookism series refresh to land, then report.
for i in $(seq 1 80); do
  if docker logs komga 2>&1 | grep -q "Apply metadata for series: Lookism"; then
    echo "FOUND apply iter=$i $(date)"
    break
  fi
  sleep 3
done
echo "--- mylar/error lines ---"
docker logs komga 2>&1 | grep -E "MylarSeriesProvider|Apply metadata for series: Lookism|InvalidFormat|RELEASING|Patched alternate|series\.json" | tail -25
echo "--- error count ---"
docker logs komga 2>&1 | grep -c "InvalidFormat" || true
