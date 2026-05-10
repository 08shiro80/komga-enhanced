#!/usr/bin/env python3
import sys, json, urllib.request

req = urllib.request.Request("https://metron.cloud/api/schema/", headers={"Accept": "application/json"})
resp = urllib.request.urlopen(req)
data = json.loads(resp.read())

for path, methods in data.get("paths", {}).items():
    for method, info in methods.items():
        params = info.get("parameters", [])
        param_names = [p.get("name", "") for p in params]
        print(f"{method.upper():6s} {path:50s} {' '.join(param_names)}")
