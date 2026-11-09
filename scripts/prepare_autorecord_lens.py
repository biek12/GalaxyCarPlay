#!/usr/bin/env python3
"""Extract this L6's numeric intrinsics into private local build assets; never copy an OEM SO.

The 616-byte v2 file and the native theta projection were checked offline. Refuse other
formats/cars rather than interpreting arbitrary 19-double records as this camera model.
"""
import argparse
import hashlib
import json
from pathlib import Path
import math
import struct

ROOT = Path(__file__).resolve().parents[1]
SHA = "e4279ca956518b83b048936aeb08c2ed1765662610b5d2d0ed71560845791c87"

def extract(raw: bytes, camera_ids: dict[str, str]) -> dict:
    if len(raw) != 616 or hashlib.sha256(raw).hexdigest() != SHA:
        raise ValueError("calibration_not_verified_for_this_l6")
    result = {}
    for index, direction in enumerate(("left", "right", "front", "rear")):
        p = struct.unpack_from("<19d", raw, 8 + index * 152)
        if not all(math.isfinite(x) for x in p) or p[4] != 0 or p[5] != 1 or any(p[n] != 0 for n in (8, 9, 12)):
            raise ValueError("unsupported_projection_terms")
        # Native 0x18b7c8/0x27a200: theta*(p5+p6*t²+p7*t⁴+p10*t⁶+p11*t⁸).
        result[direction] = dict(schema=1, model="opencv-fisheye4", cameraId=camera_ids[direction],
            width=1280, height=800, fx=p[0], fy=p[1], cx=p[2], cy=p[3],
            k=[p[6], p[7], p[10], p[11]], verified=True,
            source="G733/L6-2023-Max/FlymeAuto-2.3.1;calibration-sha256:" + SHA + ";native-theta-static-verified;visual-validation-pending")
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("calibration", type=Path)
    parser.add_argument("--mapping", required=True, help='Current CameraLayout direction to Android cameraId JSON; physical direction validation remains separate')
    args = parser.parse_args()
    mapping = json.loads(args.mapping)
    if set(map(str,mapping.values()))!={"0","1","2","3"} or set(mapping) != {"front", "rear", "left", "right"} or len(set(map(str, mapping.values()))) != 4:
        raise ValueError("invalid_camera_mapping")
    profiles = extract(args.calibration.read_bytes(), {k: str(v) for k, v in mapping.items()})
    target = ROOT / ".work/autorecord-lens-assets/lens"
    target.mkdir(parents=True, exist_ok=True)
    for direction, profile in profiles.items():
        (target / f"camera-{profile['cameraId']}.json").write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n")
    print("Prepared four numeric lens profiles in private build assets; visual validation pending.")

if __name__ == "__main__":
    main()
