#!/usr/bin/env python3
"""Extract the fixed G733 music origins from the local OEM prefab."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
from g733_scene_evidence import Kzb
from g733_background_probe import read_strings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cluster_hmi_dir", type=Path)
    args = parser.parse_args()
    pack = Kzb(args.cluster_hmi_dir, "rightmenu")
    start, prefab = pack.body("/Prefabs/Media")
    dictionary = read_strings(pack.data, pack.entries["/$property_dictionary"][1])[0]
    render_id = dictionary.index("Node2D.RenderTransformation")
    nodes = {}
    for name in ("Media_Text", "Media_Title", "Media_Artist", "Media_NoTitle"):
        signature = struct.pack("<II", pack.strings.index(name), 105)
        offset = prefab.index(signature)
        if prefab.find(signature, offset + 1) >= 0:
            raise ValueError("Ambiguous serialized music node")
        nodes[name] = start + offset
    origins = {}
    ordered = list(nodes.items())
    for index, (name, offset) in enumerate(ordered[:-1]):
        end = ordered[index + 1][1]
        signature = struct.pack("<Ifff", render_id, 1, 1, 0)
        position = pack.data.index(signature, offset, end)
        if pack.data.find(signature, position + 1, end) >= 0:
            raise ValueError("Ambiguous identity-scale render origin")
        values = struct.unpack_from("<5f", pack.data, position + 4)
        origins[name] = {"nodeOffset": hex(offset), "propertyOffset": hex(position),
                         "scale": list(values[:2]), "rotation": values[2],
                         "x": values[3], "y": values[4]}
    print(json.dumps({"rightmenuSha256": hashlib.sha256(pack.data).hexdigest(),
                      "renderPropertyId": render_id, "origins": origins,
                      "srtSetter": "0x3ae0a0", "srtToMatrix": "0x596b20",
                      "panelHeight": 140, "rowTops": [0, 26, 46, 64, 90, 108, 124],
                      "runtimeLayoutVerified": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
