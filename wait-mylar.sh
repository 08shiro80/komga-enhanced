#!/usr/bin/env bash
# Wait until Komga has applied metadata for Lookism, then dump relevant lines.
for i in $(seq 1 60); do
  if docker logs --tail 200 komga 2>&1 | grep -q "Apply metadata for series: Lookism"; then
    echo "FOUND apply at iter=$i $(date)"
    break
  fi
  sleep 3
done
echo "---"
docker logs --tail 800 komga 2>&1 \
  | grep -E "MylarSeriesProvider|Apply metadata for series: Lookism|InvalidFormat|Patched alternate|series\.json" \
  | tail -25
