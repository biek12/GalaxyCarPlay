"""编译并执行本仓库自有HAB源码测试；不加载样本SO、不打开设备、不连接车辆。"""
from pathlib import Path
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "galaxy/service/src/main/cpp/hab"
TESTS = ROOT / "galaxy/service/src/test/cpp/hab"


class HabNativeSourceTest(unittest.TestCase):
    def compile_and_run(self, name, sources, extra_flags=()):
        compiler = shlex.split(os.environ.get("CC", "cc"))
        self.assertTrue(compiler and shutil.which(compiler[0]), "需要本机C11编译器运行HAB源码回归")
        flags = shlex.split(os.environ.get("GALAXY_HAB_TEST_CFLAGS", ""))
        with tempfile.TemporaryDirectory(prefix="galaxy-hab-tests-") as directory:
            output = Path(directory) / name
            build = subprocess.run(
                [*compiler, "-std=c11", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
                 *flags, *extra_flags, "-I", str(NATIVE), *map(str, sources), "-o", str(output)],
                capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)
            result = subprocess.run([str(output)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("passed", result.stdout)

    def test_no_private_sender_loading_or_packaging(self):
        build = (ROOT / "common/build.gradle.kts").read_text()
        self.assertNotIn("galaxyLocalHab", build)
        self.assertNotIn(".local/decoded/AutoDisplay", build)
        for path in NATIVE.iterdir():
            if path.suffix in {".c", ".h"}:
                self.assertIsNone(re.search(r"\b(?:dlopen|dlsym)\s*\(", path.read_text()), path)
        self.assertFalse((ROOT / "galaxy/common/src/main/java/com/simplerbit/autodisplay/stream/HabRawFrameStreamService.java").exists())
        self.assertFalse((ROOT / "galaxy/common/src/main/java/com/shilapi/xcertplay/GalaxyL6HabNativeSender.kt").exists())
        for name in ("libautodisplaycamera.so", "libuhab.so"):
            self.assertFalse(list((ROOT / "galaxy/common/src").rglob(name)), name)

    def test_transport_abi_and_lifecycle(self):
        self.compile_and_run("transport", [
            NATIVE / "galaxy_hab_transport.c", NATIVE / "galaxy_hab_linux.c",
            TESTS / "test_transport.c",
        ])

    def test_vehicle_probe_key_and_argument_contracts(self):
        probes = ROOT / "tools/hab"
        self.compile_and_run("probe-common", [
            NATIVE / "galaxy_hab_hmac.c", probes / "probe_common.c", probes / "test_probe_common.c",
        ], ["-I", str(probes)])

    def test_hmac_standard_vectors_and_boundaries(self):
        self.compile_and_run("hmac", [
            NATIVE / "galaxy_hab_hmac.c", TESTS / "test_hmac.c",
        ])

    def test_owned_receiver_with_real_sender_and_hmac(self):
        self.compile_and_run("receiver", [
            NATIVE / "galaxy_hab_protocol.c", NATIVE / "galaxy_hab_pixels.c",
            NATIVE / "galaxy_hab_transport.c", NATIVE / "galaxy_hab_sender.c",
            NATIVE / "galaxy_hab_hmac.c", NATIVE / "galaxy_hab_receiver.c",
            TESTS / "test_receiver.c",
        ])

    def test_qnx_platform_lifecycle_and_writable_buffers(self):
        self.compile_and_run("qnx", [
            NATIVE / "galaxy_hab_protocol.c", NATIVE / "galaxy_hab_hmac.c",
            NATIVE / "galaxy_hab_receiver.c", NATIVE / "galaxy_hab_qnx.c",
            TESTS / "test_qnx.c",
        ], ["-I", str(TESTS / "qnx_stubs")])

    def test_qnx_entry_point_compiles_with_test_interface(self):
        compiler = shlex.split(os.environ.get("CC", "cc"))
        with tempfile.TemporaryDirectory(prefix="galaxy-qnx-main-") as directory:
            build = subprocess.run([
                *compiler, "-std=c11", "-Wall", "-Wextra", "-Wpedantic", "-Werror",
                "-I", str(TESTS / "qnx_stubs"), "-I", str(NATIVE), "-c",
                str(NATIVE / "galaxy_hab_qnx_main.c"), "-o", str(Path(directory) / "main.o"),
            ], capture_output=True, text=True, timeout=60)
            self.assertEqual(build.returncode, 0, build.stdout + build.stderr)

    def test_qnx_service_rearms_without_overlapping_owners_and_stops_on_repeated_failure(self):
        self.compile_and_run("qnx-service", [ROOT / "tools/hab/test_qnx_service.c"],
                             ["-I", str(TESTS / "qnx_stubs")])

    def test_sender_open_frame_and_close(self):
        self.compile_and_run("sender", [
            NATIVE / "galaxy_hab_protocol.c", NATIVE / "galaxy_hab_pixels.c",
            NATIVE / "galaxy_hab_transport.c", NATIVE / "galaxy_hab_sender.c",
            TESTS / "test_sender.c",
        ])

    def test_wire_protocol_and_signing_input(self):
        self.compile_and_run("protocol", [
            NATIVE / "galaxy_hab_protocol.c", TESTS / "test_protocol.c",
        ])

    def test_pixels_stride_alpha_and_bounds(self):
        self.compile_and_run("pixels", [
            NATIVE / "galaxy_hab_pixels.c", TESTS / "test_pixels.c",
        ])


if __name__ == "__main__":
    unittest.main()
