#!/usr/bin/env bash
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:25600/)
  if [ "$code" = "200" ] || [ "$code" = "302" ] || [ "$code" = "303" ]; then
    echo "ready iter=$i code=$code $(date)"
    break
  fi
  sleep 2
done
echo "=== application.jar inside container ==="
docker exec komga ls -la /app/application.jar 2>&1
echo "=== container image SHA now ==="
docker inspect komga --format '{{.Image}}'
