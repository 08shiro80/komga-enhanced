#!/usr/bin/env bash
# Wait until One Piece series metadata refresh applies, then check the link.
for i in $(seq 1 80); do
  if docker logs komga 2>&1 | grep -q "Apply metadata for series: One Piece"; then
    echo "FOUND iter=$i $(date)"
    break
  fi
  sleep 3
done
echo "--- recent Mylar/InvalidFormat lines ---"
docker logs komga 2>&1 | grep -E "MylarSeriesProvider|Apply metadata for series: One Piece|InvalidFormat" | tail -10
