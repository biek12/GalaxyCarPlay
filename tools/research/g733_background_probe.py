#!/usr/bin/env python3
"""Build a single-image G733 KZB research sample without loading OEM code."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zlib


OEM_SHA256 = "f8e89db53d1e2d25f23b2042baf1fcc5944a0f9a2fec8a921ed78677e5c1202a"
TARGET = "/Textures/Dark_BG1"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
PNG_HEADER = struct.pack(">IIBBBBB", 1920, 532, 8, 2, 0, 0, 0)
KZB_ROW = struct.Struct("<6I")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_strings(data, offset):
    count, = struct.unpack_from("<I", data, offset)
    offset += 4
    strings = []
    for _ in range(count):
        end = data.index(b"\0", offset)
        strings.append(data[offset:end].decode("utf-8"))
        offset = end + 1
    return strings, offset


def read_directory(data):
    magic, major, minor, name_size = struct.unpack_from("<4sHHI", data)
    name_end = 12 + name_size
    if (magic, major, minor, data[12:name_end]) != (b"KZBF", 3, 2, b"background"):
        raise ValueError("Unexpected G733 KZB header")
    names, offset = read_strings(data, name_end)
    count, = struct.unpack_from("<I", data, offset)
    start = offset + 4
    end = start + count * KZB_ROW.size
    entries = {}
    for name_index, kind, pos, size, _, _ in KZB_ROW.iter_unpack(data[start:end]):
        name = names[name_index]
        if name in entries or not end <= pos <= pos + size <= len(data):
            raise ValueError(f"Invalid KZB entry {name}")
        entries[name] = kind, pos, size
    if len(entries) != 49 or count != len(entries):
        raise ValueError("Unexpected background directory size")
    return entries


def png_header(png):
    if not png.startswith(PNG_MAGIC):
        raise ValueError("Missing PNG signature")
    offset = len(PNG_MAGIC)
    header = None
    while offset + 12 <= len(png):
        size, kind = struct.unpack_from(">I4s", png, offset)
        end = offset + 8 + size
        if end + 4 > len(png):
            break
        crc, = struct.unpack_from(">I", png, end)
        if zlib.crc32(png[offset + 4:end]) != crc:
            raise ValueError(f"PNG {kind!r} CRC mismatch")
        if kind == b"IHDR" and offset == len(PNG_MAGIC) and size == 13:
            header = png[offset + 8:end]
        if kind == b"IEND" and size == 0 and end + 4 == len(png) and header:
            return header
        offset = end + 4
    raise ValueError("Incomplete PNG")


def texture_images(data, directory):
    _, offset, _ = directory["/$strings"]
    strings, _ = read_strings(data, offset)
    images = {}
    for texture, (kind, pos, size) in directory.items():
        if kind != 16 or size != 36 or not texture.startswith("/Textures/"):
            continue
        url = strings[struct.unpack_from("<I", data, pos + 32)[0]]
        if not re.fullmatch(r"kzb://background/Resource Files/Images/.+\.png", url):
            raise ValueError(f"Unexpected texture reference {url}")
        path = url.removeprefix("kzb://background")
        _, entry, entry_size = directory[path]
        length, = struct.unpack_from("<I", data, entry + 16)
        if length != entry_size - 20:
            raise ValueError(f"KZB image size mismatch for {path}")
        png = data[entry + 20:entry + entry_size]
        header = png_header(png)
        images[texture] = {
            "resource": path,
            "offset": entry + 20,
            "bytes": length,
            "format": list(struct.unpack(">IIBBBBB", header)),
            "sha256": sha256(png),
        }
    if len(images) != 10:
        raise ValueError("Unexpected background texture count")
    return images


def png_chunk(kind, payload):
    content = kind + payload
    return struct.pack(">I", len(payload)) + content + struct.pack(">I", zlib.crc32(content))


def test_png(size):
    row_width, row_count = struct.unpack_from(">II", PNG_HEADER)
    # Visually distinguish this sample without importing an external asset.
    rows = b"".join(
        b"\0" + bytes((16 + y // 32, 31 + y // 22, 48 + y // 11)) * row_width
        for y in range(row_count)
    )
    image = PNG_MAGIC + png_chunk(b"IHDR", PNG_HEADER) + png_chunk(b"IDAT", zlib.compress(rows, 9))
    end = png_chunk(b"IEND", b"")
    label = b"Comment\0G733 offline background probe "
    padding = size - len(image) - len(end) - 12 - len(label)
    if padding < 0:
        raise ValueError("Probe PNG exceeds the fixed KZB image span")
    return image + png_chunk(b"tEXt", label + b" " * padding) + end


def image_png(path, size):
    image = path.read_bytes()
    if png_header(image) != PNG_HEADER:
        raise ValueError("Replacement must be a complete 1920x532 RGB8 PNG")
    if len(image) == size:
        return image
    label = b"Comment\0G733 generated background "
    padding = size - len(image) - 12 - len(label)
    if padding < 0:
        raise ValueError("Replacement PNG exceeds the original image span")
    return image[:-12] + png_chunk(b"tEXt", label + b" " * padding) + image[-12:]


def validate_candidate(original, candidate):
    if sha256(original) != OEM_SHA256 or len(candidate) != len(original):
        raise ValueError("Unexpected original background or candidate length")
    directory = read_directory(original)
    before = texture_images(original, directory)
    target = before[TARGET]
    start, end = target["offset"], target["offset"] + target["bytes"]
    new_directory = read_directory(candidate)
    after = texture_images(candidate, new_directory)
    changed = [texture for texture in before if before[texture] != after[texture]]
    if (new_directory != directory or changed != [TARGET] or
            after[TARGET]["format"] != target["format"] or
            png_header(candidate[start:end]) != PNG_HEADER or
            candidate[:start] != original[:start] or candidate[end:] != original[end:]):
        raise ValueError("Candidate changed more than the selected RGB8 PNG")
    return directory, before, after, start, end


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("background_kzb", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--image", type=Path, help="Exact 1920x532 RGB8 PNG fitting the original slot")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    private_root = Path(__file__).resolve().parents[2] / ".local"
    if not output.is_relative_to(private_root) or output == private_root:
        parser.error("output_dir must be a new directory under the repository's .local/")
    original = args.background_kzb.read_bytes()
    if sha256(original) != OEM_SHA256:
        parser.error("background.kzb does not match the fixed G733 research baseline")

    directory = read_directory(original)
    before = texture_images(original, directory)
    target = before[TARGET]
    start = target["offset"]
    end = start + target["bytes"]
    replacement = image_png(args.image, target["bytes"]) if args.image else test_png(target["bytes"])
    candidate = original[:start] + replacement + original[end:]
    directory, before, after, start, end = validate_candidate(original, candidate)

    output.mkdir(parents=True)
    (output / "dark-bg1-probe.png").write_bytes(replacement)
    (output / "background-probe.kzb").write_bytes(candidate)
    report = {
        "originalSha256": OEM_SHA256,
        "probeSha256": sha256(candidate),
        "unchangedDirectoryEntries": len(directory),
        "changedTexture": TARGET,
        "changedPngSpan": [start, end],
        "originalTextures": before,
        "replacement": after[TARGET],
        "validatedOnQnx": False,
    }
    (output / "evidence.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{TARGET}: only PNG range [{start:#x}, {end:#x}) can differ")
    print(f"Candidate SHA-256 {report['probeSha256']}")


if __name__ == "__main__":
    main()
