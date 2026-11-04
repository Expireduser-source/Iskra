"""Turns the Android Lint XML report into a few GitHub annotations (readable through the REST API).

GitHub keeps at most 10 warning annotations per step, so issues are grouped by id.
"""
import collections
import os
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
if not os.path.exists(path):
    print("no lint report at " + path)
    sys.exit(0)

groups = collections.OrderedDict()
for issue in ET.parse(path).getroot().iter("issue"):
    key = (issue.get("severity", "?"), issue.get("id", "?"))
    loc = issue.find("location")
    where = "?"
    if loc is not None:
        where = loc.get("file", "?").split("/src/main/")[-1] + ":" + (loc.get("line") or "?")
    groups.setdefault(key, []).append(where + " " + (issue.get("message") or "")[:220])

items = sorted(groups.items(), key=lambda kv: (kv[0][0] not in ("Fatal", "Error"), -len(kv[1])))
lines = ["[%s] %s x%d: %s" % (sev, iid, len(locs), " | ".join(locs[:20])) for (sev, iid), locs in items]
chunks = lines[:9] + (["\n".join(lines[9:])] if len(lines) > 9 else [])
for c in chunks:
    c = c.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print("::warning title=Android Lint::" + c)
print("lint: %d issues of %d kinds" % (sum(len(v) for v in groups.values()), len(groups)))
