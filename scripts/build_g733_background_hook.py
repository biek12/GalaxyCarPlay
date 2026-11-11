#!/usr/bin/env python3
"""Build and verify the fixed G733 QNX preload probe offline."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "tools/hab"))
from elf64 import ELF
sys.path.insert(0, str(ROOT / "tools/research"))
from g733_background_probe import validate_candidate

HMI_PATH = "apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi"
HMI_HASH = "385d8fc526936a32fa08cf8dfd51db731a58c8a3c86fd43709a6269bdf080e64"
LIBC_HASH = "ce94da81ea340fbf94637953ba5fc9004b7596f3c2b6e624253cb7ea30d77c86"
IMPORTS = {"__get_errno_ptr", "dlsym", "getenv", "getcwd", "lseek", "write",
           "strlen", "strcmp", "read", "close", "getpagesize", "mprotect",
           "memcpy", "memset", "memcmp"}
SOURCE = ROOT / "tools/research/g733_background_hook.c"
SCENE = SOURCE.with_name("g733_scene_hook.cpp")


def music_bridge(hmi, out):
    component = ROOT / "galaxy/common/src/main/cpp/kanzi_music/music_component.cpp"
    source = component.read_text()
    placement = "inline void *operator new(size_t, void *p) noexcept { return p; }"
    if source.count(placement) != 1:
        raise ValueError("Music component placement-new declaration changed")
    (out / "music-reuse.cpp").write_text(source.replace(placement, ""))
    imported = ["".join(re.findall(r'"([^"\n]*)"', match))
                for match in re.findall(r"asm\((.*?)\)", source + (component.parent / "compact_music_region.h").read_text(), re.S)]
    sections = [hmi.unpack("<IIQQQQIIQQ", hmi.h[5] + i * hmi.h[10])
                for i in range(hmi.h[11])]
    symbols = {}
    for section in sections:
        if section[1] != 2:
            continue
        strings = hmi.take(sections[section[6]][4], sections[section[6]][5])
        for offset in range(section[4], section[4] + section[5], section[9]):
            name, info, _, _, value, size = hmi.unpack("<IBBHQQ", offset)
            end = strings.index(0, name)
            symbols[strings[name:end].decode()] = (info & 15, value, size)
    assembly = [".text"]
    binding = ["#include <stdint.h>", 'extern "C" {', "#include <string.h>", "}",
               'extern "C" { __attribute__((visibility("hidden"))) uintptr_t g733_music_base; }']
    assignments = []
    evidence = []
    for index, name in enumerate(imported):
        kind, value, size = symbols[name]
        evidence.append({"name": name, "address": hex(value), "kind": kind, "size": size})
        assembly.extend([f".global {name}", f".hidden {name}"])
        if kind == 2:
            assembly.extend([f".type {name},%function", ".balign 4", f"{name}:",
                             "adrp x16, g733_music_base",
                             "ldr x16, [x16, :lo12:g733_music_base]",
                             f"movz x17, #{value & 65535}",
                             f"movk x17, #{value >> 16}, lsl #16",
                             "add x16, x16, x17", "br x16"])
        elif kind == 1 and size == 8:
            assembly.extend([".data", ".balign 8", f".type {name},%object",
                             f".size {name},8", f"{name}: .zero 8", ".text"])
            binding.append(f'extern "C" char p{index}[] asm("{name}");')
            assignments.append(f"memcpy(p{index}, (const void *)(base + {value}), 8);")
        else:
            raise ValueError(f"Unsupported music ABI symbol {name}")
    binding.extend(['extern "C" void g733_music_bind(uintptr_t base) {',
                    "g733_music_base = base;", *assignments, "}"])
    (out / "music-bridge.S").write_text("\n".join(assembly) + "\n")
    (out / "music-binding.cpp").write_text("\n".join(binding) + "\n")
    (out / "music-abi.json").write_text(json.dumps(evidence, indent=2) + "\n")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--music-probe", action="store_true", help="Include the parked music-node fixture")
    parser.add_argument("--live-music", action="store_true", help="Consume current GKM1 instead of fixture labels")
    args = parser.parse_args()
    if args.live_music and not args.music_probe: parser.error("--live-music requires --music-probe")
    out = args.output.resolve()
    if ROOT / ".local" not in out.parents:
        parser.error("output must be a new directory under .local")

    hmi = ELF(args.rootfs / HMI_PATH)
    libc = ELF(args.rootfs / "lib64/libc.so.4")
    candidate = args.candidate.read_bytes()
    if (digest(hmi.b), digest(libc.b)) != (HMI_HASH, LIBC_HASH):
        parser.error("input hashes differ from the verified G733 profile")
    validate_candidate((args.rootfs / HMI_PATH).with_name("background.kzb").read_bytes(), candidate)
    if not {"open", "eglSwapBuffers"} <= {rel["symbol"] for rel in hmi.relocs if rel["table"] == "JMPREL"}:
        parser.error("HMI imports changed")
    slots = {r["offset"]: r["addend"] for r in hmi.relocs if r["table"] == "RELA"}
    if (slots.get(0xa28e20), slots.get(0xa28e08)) != (0x27d2a0, 0x27d2a8):
        parser.error("ClusterFunction lifecycle slots changed")
    if (slots.get(0xa28de8), slots.get(0xa28df0)) != (0x15c0b0, 0x15c0c8):
        parser.error("ClusterFunction destructor slots changed")
    imports_needed = set(IMPORTS)
    if args.music_probe:
        imports_needed.update({"malloc", "free", "time", "rename", "fopen", "fwrite", "fclose", "getpid", "getuid"})
    if not imports_needed <= {sym["name"] for sym in libc.syms if sym["defined"] and sym["visibility"] == 0}:
        parser.error("QNX libc imports unavailable")
    fingerprint = 14695981039346656037
    for byte in candidate:
        fingerprint = ((fingerprint ^ byte) * 1099511628211) & ((1 << 64) - 1)

    out.mkdir(parents=True)
    llvm = ROOT / ".work/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin"
    clang = llvm / "clang"
    lld = llvm / "ld.lld"
    resource = Path(run(clang, "-print-resource-dir", capture_output=True, text=True).stdout.strip())
    imports = out / "link-only.S"
    imports.write_text(".text\n" + "".join(
        f".global {name}\n.type {name},%function\n{name}:\nret\n" for name in sorted(imports_needed)
    ), encoding="utf-8")
    run(clang, "--target=aarch64-unknown-none-elf", "-c", imports, "-o", out / "link-only.o")
    run(lld, "-shared", "-soname", "libc.so.4", out / "link-only.o", "-o", out / "libc-link-only.so")
    flags = ["--target=aarch64-unknown-nto", "-ffreestanding", "-nostdinc",
             "-isystem", resource / "include", "-I", ROOT / "tools/hab/qnx_abi",
             "-O2", "-fPIC", "-mno-outline-atomics", "-fno-stack-protector",
             "-Wall", "-Wextra", "-Werror"]
    if args.music_probe:
        flags.append("-DG733_MUSIC_PROBE")
        if args.live_music: flags.extend(["-DG733_LIVE_MUSIC","-DG733_RESOURCE_BACKGROUND"])
    run(clang, *flags,
        "-DGALAXY_QNX_ABI_CANDIDATE", f"-DG733_CANDIDATE_SIZE={len(candidate)}",
        f"-DG733_CANDIDATE_FNV=0x{fingerprint:x}ULL", "-c", SOURCE, "-o", out / "hook.o")
    run(llvm / "clang++", *flags, "-std=c++11", "-fvisibility=hidden", "-fno-exceptions", "-fno-rtti",
        "-c", SCENE, "-o", out / "scene.o")
    music_objects = []
    if args.music_probe:
        music_bridge(hmi, out)
        music_folder = ROOT / "galaxy/common/src/main/cpp/kanzi_music"
        music_flags = [*flags, "-I", out, "-I", music_folder, "-I", music_folder.parent / "kanzi_background",
                       '-DGKM_PHASE_PATH="/tmp/galaxy-g733-music-probe.log"', '-DG733_VIRTUAL_SCENE_API']
        for source, name in [(SOURCE.with_name("g733_music_probe.cpp"), "music-probe.o"),
                             (out / "music-binding.cpp", "music-binding.o")]:
            run(llvm / "clang++", *music_flags, "-std=c++11", "-fvisibility=hidden",
                "-fno-exceptions", "-fno-rtti", "-c", source, "-o", out / name)
            music_objects.append(out / name)
        for source, name in [(music_folder / "music_frame.c", "music-frame.o"),
                             (out / "music-bridge.S", "music-bridge.o")]:
            run(clang, *music_flags, "-c", source, "-o", out / name)
            music_objects.append(out / name)
    exports_map = out / "exports.map"
    exports_map.write_text("{ global: open; eglSwapBuffers; local: *; };\n")
    binary = out / "libgalaxy_g733_background_probe.so"
    run(lld, "-shared", "-soname", binary.name, "-z", "defs", "-z", "now", "-z", "norelro",
        "--no-rosegment", "-z", "separate-loadable-segments", "-z", "max-page-size=4096",
        "--hash-style=gnu", "--version-script=" + str(exports_map), out / "hook.o", out / "scene.o",
        *music_objects, out / "libc-link-only.so", "-o", binary)

    elf = ELF(binary)
    needed = [elf.string(tag) for tag in elf.dt.get(1, [])]
    exports = {sym["name"] for sym in elf.syms if sym["defined"] and sym["visibility"] == 0}
    undefined = {sym["name"] for sym in elf.syms if not sym["defined"] and sym["name"]}
    if (elf.h[:2] != (3, 183) or len([p for p in elf.ph if p["type"] == 1]) != 2 or
            needed != ["libc.so.4"] or exports != {"open", "eglSwapBuffers"} or
            not undefined <= imports_needed or elf.one(27) != 8):
        raise ValueError("Unexpected QNX preload ELF ABI")
    for temporary in ("link-only.S", "link-only.o", "libc-link-only.so", "hook.o", "scene.o"):
        (out / temporary).unlink()

    sandbox = out / "host"
    sandbox.mkdir()
    test_binary = out / "host-test"
    run("clang", "-O1", "-g", "-fsanitize=address,undefined", "-Wall", "-Wextra", "-Werror",
        f'-DG733_OEM_DIR="{sandbox}"', f'-DG733_CANDIDATE="{sandbox / "candidate.kzb"}"',
        SOURCE.with_name("test_g733_background_hook.c"), "-o", test_binary)
    test = run(test_binary, capture_output=True, text=True)
    stages = test.stderr.splitlines()
    if stages != ["G733_ORIGINAL_BACKGROUND_OPEN", "G733_CANDIDATE_BACKGROUND_OPEN",
                  "G733_EGL_SWAP_AFTER_RESOURCE_OPEN", "G733_CANDIDATE_REJECTED"]:
        raise ValueError("Unexpected host stage order")
    scene_test = out / "host-scene-test"
    run("clang++", "-std=c++11", "-O1", "-g", "-fsanitize=address,undefined",
        "-Wall", "-Wextra", "-Werror", SCENE.with_name("test_g733_scene_hook.cpp"), "-o", scene_test)
    scene_result = run(scene_test, capture_output=True, text=True)
    (out / "host-scene.log").write_text(scene_result.stderr, encoding="utf-8")
    music_result = None
    if args.music_probe:
        host_flags = ["-O1", "-g", "-fsanitize=address,undefined", "-Wall", "-Wextra", "-Werror"]
        music_folder = ROOT / "galaxy/common/src/main/cpp/kanzi_music"
        run("clang", *host_flags, "-I", music_folder, "-I", music_folder.parent / "kanzi_background", "-c", music_folder / "music_frame.c",
            "-o", out / "host-music-frame.o")
        run("clang++", *host_flags, "-DGKM_LEGACY_TEXT_LAYOUT", "-std=c++11", "-I", music_folder, "-I", music_folder.parent / "kanzi_background",
            f'-DGKM_STATE_PATH="{sandbox / "music-state.bin"}"',
            f'-DGKM_PHASE_PATH="{sandbox / "music-status.log"}"',
            ROOT / "tools/kanzi_music/test_music_component.cpp", out / "host-music-frame.o",
            "-o", out / "host-music-test")
        music_result = run(out / "host-music-test", capture_output=True, text=True)
        (out / "host-music.log").write_text(music_result.stdout + music_result.stderr)
        run("clang++", *host_flags, "-std=c++11", "-I", music_folder, "-I", music_folder.parent / "kanzi_background",
            f'-DGKM_STATE_PATH="{sandbox / "compact-state.bin"}"',
            f'-DGKM_PHASE_PATH="{sandbox / "compact-status.log"}"',
            f'-DGKM_COVER_PATH="{sandbox / "compact-cover.bin"}"',
            ROOT / "tools/kanzi_music/test_compact_music.cpp", out / "host-music-frame.o",
            "-o", out / "host-compact-test")
        compact_result=run(out / "host-compact-test",capture_output=True,text=True)
        (out / "host-compact.log").write_text(compact_result.stdout + compact_result.stderr)
    crc = run("cksum", binary, capture_output=True, text=True).stdout.split()[:2]
    (out / "hook.cksum").write_text(" ".join(crc) + "\n", encoding="ascii")
    controller = ROOT / "tools/research/g733_scene_trial.sh"
    run("sh", "-n", controller)
    controller_test = run(sys.executable, controller.with_name("test_g733_scene_trial.py"),
                          capture_output=True, text=True).stdout.strip()
    (out / controller.name).write_bytes(controller.read_bytes())
    report = {"sha256": digest(elf.b), "hmiSha256": HMI_HASH, "candidateSha256": digest(candidate),
              "needed": needed, "hostStages": stages, "hostScene": scene_result.stdout.strip(),
              "hostController": controller_test,
              "hostMusicPassed": music_result is not None and music_result.returncode == 0,
              "hostCompactPassed": bool(args.music_probe), "qnxExecuted": False}
    source_files = [SOURCE, SCENE, Path(__file__).resolve(), controller,
                    SOURCE.with_name("g733_background_probe.py"), ROOT / "tools/hab/elf64.py",
                    SOURCE.with_name("test_g733_background_hook.c"),
                    SCENE.with_name("test_g733_scene_hook.cpp"),
                    controller.with_name("test_g733_scene_trial.py")]
    if args.music_probe:
        source_files.extend([SOURCE.with_name("g733_music_probe.cpp"),
                             music_folder / "music_component.cpp", music_folder / "music_frame.c",
                             music_folder / "music_frame.h", music_folder / "node_replacement.h", music_folder / "compact_music_region.h",
                             ROOT / "tools/kanzi_music/test_music_component.cpp",
                             ROOT / "tools/kanzi_music/test_compact_music.cpp"])
        report["rightmenuSha256"] = digest((args.rootfs / HMI_PATH).with_name("rightmenu.kzb").read_bytes())
    source_files += list((ROOT / "galaxy/common/src/main/cpp/kanzi_background").glob("*.h"))
    report["sourceSha256"] = {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in source_files}
    (out / "evidence.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(test.stdout.strip())
    print(scene_result.stdout.strip())
    print(controller_test)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
