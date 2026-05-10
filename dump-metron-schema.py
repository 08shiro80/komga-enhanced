#!/usr/bin/env python3
import sys, json, urllib.request

req = urllib.request.Request("https://metron.cloud/api/schema/", headers={"Accept": "application/json"})
resp = urllib.request.urlopen(req)
data = json.loads(resp.read())

# Print all top-level keys and their types
for key, val in data.items():
    if isinstance(val, dict):
        print(f"{key}: dict with {len(val)} keys")
        if key == "paths":
            for path, methods in val.items():
                print(f"  {path}")
                for method, info in methods.items():
                    params = info.get("parameters", [])
                    param_str = ", ".join([p.get("name", "") for p in params])
                    print(f"    {method.upper()}: {param_str}")
    elif isinstance(val, list):
        print(f"{key}: list[{len(val)}]")
    else:
        print(f"{key}: {str(val)[:100]}")
