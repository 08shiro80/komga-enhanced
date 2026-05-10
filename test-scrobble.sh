#!/bin/bash
# Test script for comic scrobbler
# Usage: ./test-scrobble.sh <email> <password>

set -e

BASE="http://localhost:25600"
EMAIL="${1:-admin@komga.org}"
PASS="${2:-admin123}"

echo "=== Logging into Komga ==="
TOKEN=$(curl -s -X POST "$BASE/api/v1/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)

if [ -z "$TOKEN" ]; then
  echo "Login failed. Trying other common passwords..."
  TOKEN=$(curl -s -X POST "$BASE/api/v1/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@komga.org","password":"admin"}' | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
fi

if [ -z "$TOKEN" ]; then
  echo "Login failed. Please provide correct credentials."
  echo "Usage: $0 <email> <password>"
  exit 1
fi

echo "Token: ${TOKEN:0:20}..."

# Search for Batman series
echo ""
echo "=== Searching for Batman series ==="
BATMAN=$(curl -s "$BASE/api/v1/series?search=Batman&size=5" \
  -H "Authorization: Bearer $TOKEN")
echo "$BATMAN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('content', [])
print(f'Found {len(content)} series matching Batman:')
for s in content:
    print(f'  - {s[\"name\"]} (id={s[\"id\"]})')
"

# Get the first series
SERIES_ID=$(echo "$BATMAN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('content', [])
if content:
    print(content[0]['id'])
else:
    print('')
" 2>/dev/null)

if [ -z "$SERIES_ID" ]; then
  echo "No Batman series found. Trying Absolute Batman..."
  BATMAN=$(curl -s "$BASE/api/v1/series?search=Absolute+Batman&size=5" \
    -H "Authorization: Bearer $TOKEN")
  echo "$BATMAN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('content', [])
print(f'Found {len(content)} series:')
for s in content:
    print(f'  - {s[\"name\"]} (id={s[\"id\"]})')
"
  SERIES_ID=$(echo "$BATMAN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('content', [])
if content:
    print(content[0]['id'])
else:
    print('')
" 2>/dev/null)
fi

if [ -z "$SERIES_ID" ]; then
  echo "No series found. Listing all series..."
  ALL=$(curl -s "$BASE/api/v1/series?size=20" -H "Authorization: Bearer $TOKEN")
  echo "$ALL" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for s in data.get('content', []):
    print(f'  - {s[\"name\"]} (id={s[\"id\"]})')
" 2>/dev/null
fi

# Get books in the series
echo ""
echo "=== Books in series $SERIES_ID ==="
BOOKS=$(curl -s "$BASE/api/v1/series/$SERIES_ID/books?size=5" \
  -H "Authorization: Bearer $TOKEN")
echo "$BOOKS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for b in data.get('content', []):
    meta = b.get('metadata', {})
    print(f'  - Book {b.get(\"number\", \"?\")}: {meta.get(\"title\", \"untitled\")} (id={b[\"id\"]}, completed={b.get(\"progress\", {}).get(\"completed\", False)})')
" 2>/dev/null

echo ""
echo "=== Plugin Logs (comic-scrobbler) ==="
LOGS=$(curl -s "$BASE/api/v1/plugins/comic-scrobbler/logs?size=10" \
  -H "Authorization: Bearer $TOKEN")
echo "$LOGS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
logs = data if isinstance(data, list) else data.get('content', [])
print(f'Found {len(logs)} log entries:')
for log in logs:
    print(f'  [{log.get(\"logLevel\", \"?\")}] {log.get(\"message\", \"\")}')
" 2>/dev/null || echo "No logs or endpoint differs"
