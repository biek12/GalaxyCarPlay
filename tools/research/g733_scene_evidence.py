#!/usr/bin/env python3
"""Resolve G733 scene and independent SR asset references from local KZB directories."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

sys.dont_write_bytecode = True
from g733_background_probe import KZB_ROW, read_strings

SELECTED = {
    "clusterfunction": (
        "/StartupPrefab", "/Prefabs/E_POWER_STATUS_ACTIVE", "/Prefabs/E_POWER_STATUS_DRIVING",
        "/Prefabs/module_BG", "/Prefabs/module_BackGround", "/Prefabs/module_BackgroundDockBar",
        "/Prefabs/module_BgRoad", "/Prefabs/module_ADAS2D", "/Prefabs/module_ADAS3D",
        "/Prefabs/module_Warning",
    ),
    "background": ("/Prefabs/BG", "/Prefabs/BG1", "/Prefabs/BGMask", "/Prefabs/OtherBG"),
    "adas": ("/Screens/Screen/<ResourceDictionaryInNode>", "/Prefabs/ADASRoot2D", "/Prefabs/ADASRoot3D",
             "/Prefabs/MyCar2D", "/Render Passes/ADAS Render Pass"),
    "resource": (),
    "warning": ("/Prefabs/WarningRoot",),
}


class Kzb:
    def __init__(self, folder, name):
        self.data = (folder / f"{name}.kzb").read_bytes()
        magic, major, minor, size = struct.unpack_from("<4sHHI", self.data)
        if (magic, major, minor, self.data[12:12 + size]) != (b"KZBF", 3, 2, name.encode()):
            raise ValueError(f"Unexpected header for {name}")
        names, cursor = read_strings(self.data, 12 + size)
        count, = struct.unpack_from("<I", self.data, cursor)
        start = cursor + 4
        end = start + count * KZB_ROW.size
        self.entries = {}
        for index, kind, offset, length, _, _ in KZB_ROW.iter_unpack(self.data[start:end]):
            path = names[index]
            if path in self.entries or not end <= offset <= offset + length <= len(self.data):
                raise ValueError(f"Invalid directory entry {path}")
            self.entries[path] = (kind, offset, length)
        if len(self.entries) != count:
            raise ValueError("Invalid directory length")
        self.strings, _ = read_strings(self.data, self.entries["/$strings"][1])

    def body(self, key):
        _, start, length = self.entries[key]
        return start, self.data[start:start + length]

    def literal_refs(self, key):
        offset, body = self.body(key)
        refs = []
        for match in re.finditer(b"kzb://", body):
            start = match.start()
            if start < 4:
                continue
            length, = struct.unpack_from("<I", body, start - 4)
            url = body[start:start + length]
            if length == len(url) and 7 < length <= 256 and all(32 <= b < 127 for b in url):
                refs.append({"offset": hex(offset + start), "url": url.decode("ascii")})
        return refs

    def indexed_pair(self, key, name, url):
        offset, body = self.body(key)
        pair = struct.pack("<II", self.strings.index(name), self.strings.index(url))
        pos = body.index(pair)
        if body.find(pair, pos + 1) != -1:
            raise ValueError(f"Ambiguous reference to {name}")
        return {"offset": hex(offset + pos), "name": name, "url": url}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cluster_hmi_dir", type=Path)
    args = parser.parse_args()
    packs = {name: Kzb(args.cluster_hmi_dir, name) for name in SELECTED}
    project = packs["clusterfunction"]
    driving = "/Prefabs/E_POWER_STATUS_DRIVING"
    active = "/Prefabs/E_POWER_STATUS_ACTIVE"
    instances = {
        owner: [project.indexed_pair(owner, instance, "kzb://clusterfunction/Prefabs/" + prefab)
                for instance, prefab in items]
        for owner, items in (
            (driving, (("moduleBgRoad", "module_BgRoad"), ("module_ADAS2D", "module_ADAS2D"),
                       ("module_ADAS3D", "module_ADAS3D"), ("module_Warning", "module_Warning"))),
            (active, (("module_ADAS2D", "module_ADAS2D"), ("module_ADAS3D", "module_ADAS3D"),
                      ("module_Warning", "module_Warning"))),
        )
    }
    opacity_id = read_strings(project.data, project.entries["/$property_dictionary"][1])[0].index("Node.Opacity")
    road = int(instances[driving][0]["offset"], 16)
    if struct.unpack_from("<IIf", project.data, road + 24) != (1, opacity_id, 0.0):
        raise ValueError("moduleBgRoad opacity baseline changed")

    resolved = {}
    for name, pack in packs.items():
        records = {}
        for key in SELECTED[name]:
            kind, offset, length = pack.entries[key]
            records[key] = {"kind": kind, "offset": hex(offset), "bytes": length,
                            "literalRefs": pack.literal_refs(key)}
        resolved[name] = {"sha256": hashlib.sha256(pack.data).hexdigest(),
                          "directoryCount": len(pack.entries),
                          "hasStartupPrefab": "/StartupPrefab" in pack.entries,
                          "entries": records}

    adas_refs = (packs["adas"].literal_refs("/Prefabs/ADASRoot2D") +
                 packs["adas"].literal_refs("/Prefabs/ADASRoot3D"))
    external = sorted({ref["url"] for ref in adas_refs if not ref["url"].startswith("kzb://adas/")})
    dependencies = {}
    for url in external:
        namespace, path = url.removeprefix("kzb://").split("/", 1)
        dependencies[url] = namespace in packs and ("/" + path) in packs[namespace].entries
    print(json.dumps({"packs": resolved, "instances": instances,
                      "moduleBgRoadOpacity": {"propertyId": opacity_id, "value": 0.0},
                      "srExternalLiteralReferences": dependencies,
                      "runtimeNodesVerified": False}, indent=2))


if __name__ == "__main__":
    main()
