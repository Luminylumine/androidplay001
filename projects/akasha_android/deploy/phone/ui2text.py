#!/data/data/com.termux/files/usr/bin/python
# ui2text.py - compress a uiautomator dump into an LLM-friendly text tree.
# Input: XML on stdin. Output: one line per interesting node:
#   [x1,y1][x2,y2] Class.FLAGS label
# where FLAGS: C=clickable, S=scrollable, F=focused. Tap center = midpoint of bounds.
import sys
import xml.etree.ElementTree as ET

max_lines = int(sys.argv[1]) if len(sys.argv) > 1 else 120

data = sys.stdin.read()
i = data.find("<?xml")
if i < 0:
    print("NO_UI_XML")
    sys.exit(1)

root = ET.fromstring(data[i:])
lines = []


def walk(node):
    if len(lines) >= max_lines:
        return
    text = (node.get("text") or "").strip()
    desc = (node.get("content-desc") or "").strip()
    bounds = node.get("bounds") or ""
    cls = (node.get("class") or "").split(".")[-1]
    clickable = node.get("clickable") == "true"
    scrollable = node.get("scrollable") == "true"
    focused = node.get("focused") == "true"
    if text or desc or clickable:
        flags = "".join(
            f for f, v in (("C", clickable), ("S", scrollable), ("F", focused)) if v
        )
        label = text or desc
        lines.append("%s %s%s %s" % (bounds, cls, ("." + flags) if flags else "", label))
    for child in list(node):
        walk(child)


walk(root)
if lines:
    print("\n".join(lines))
else:
    print("(empty screen)")
