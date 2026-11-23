#!/usr/bin/env python3
"""Fails when a string key is defined in more than one file of the same language folder.

Compose resources crash at runtime ("Resource with ID=... has more than one file") when that
happens, so this runs before every build.
"""
import collections
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "composeApp", "src", "commonMain", "composeResources")
PATTERN = re.compile(r'<(?:string|plurals|string-array) name="([^"]+)"')

problems = []
for folder in sorted(glob.glob(os.path.join(ROOT, "values*"))):
    seen = collections.defaultdict(list)
    for path in sorted(glob.glob(os.path.join(folder, "*.xml"))):
        with open(path, encoding="utf-8") as handle:
            for key in PATTERN.findall(handle.read()):
                seen[key].append(os.path.basename(path))
    for key, files in seen.items():
        if len(files) > 1:
            problems.append(f"{os.path.basename(folder)}: {key} in {', '.join(files)}")

if problems:
    print("Duplicate string keys (the app crashes when it shows them):")
    print("\n".join(problems))
    sys.exit(1)
print("No duplicate string keys.")
