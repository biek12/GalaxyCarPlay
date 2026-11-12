"""核对 Windows 入口只委托一次原 Shell 门槛，并保留 UTF-8 与退出码。

替身替换 Bash 进程，不执行验证流程，也不制造 JUnit 或 VALIDATION.json。
"""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ENTRY = ROOT / "scripts" / "verify_projection_retirement_windows.ps1"
CANONICAL = ROOT / "scripts" / "verify_projection_retirement.sh"
CSC = Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe")
CHINESE = "入口委托失败：验证未完成"


class WindowsEntryTest(unittest.TestCase):
    """真实 PowerShell 入口对 Bash 边界的一次委托。"""

    def test_delegates_once_and_keeps_utf8_and_exit_code(self) -> None:
        """cache/report 原样进入环境，中文和指定退出码不被入口改写。"""
        self.assertTrue(CSC.is_file(), "缺少 csc，无法构造 Bash 边界替身")
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            cache = temp / "cache dir"
            report = temp / "report dir"
            cache.mkdir()
            report.mkdir()
            record = temp / "record.txt"
            stub = self._compile_stub(temp)
            env = os.environ.copy()
            env["GALAXY_ENTRY_RECORD"] = str(record)
            completed = subprocess.run(
                [
                    "powershell",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(ENTRY),
                    "-CacheRoot",
                    str(cache),
                    "-ReportDir",
                    str(report),
                    "-BashPath",
                    str(stub),
                ],
                cwd=ROOT,
                env=env,
                capture_output=True,
                check=False,
            )
            text = completed.stdout.decode("utf-8")
            noted = record.read_text(encoding="utf-8")
            script_args = [line for line in noted.splitlines() if line.startswith("ARG=")]
            self.assertEqual(
                ["ARG=" + str(CANONICAL.resolve())],
                script_args,
                noted,
            )
            self.assertIn("CACHE=" + str(cache.resolve()).replace("\\", "/"), noted)
            self.assertIn("REPORT=" + str(report.resolve()).replace("\\", "/"), noted)
            self.assertIn(CHINESE, text)
            self.assertEqual(23, completed.returncode)
            self.assertFalse((report / "VALIDATION.json").exists())
            self.assertFalse(list(report.glob("TEST-*.xml")))

    def _compile_stub(self, temp: Path) -> Path:
        """编译只记录参数和环境的 Windows 可执行替身。"""
        source = temp / "BashBoundaryStub.cs"
        source.write_text(
            r"""
using System;
using System.IO;
using System.Text;
public class BashBoundaryStub {
    public static int Main(string[] args) {
        string record = Environment.GetEnvironmentVariable("GALAXY_ENTRY_RECORD");
        var builder = new StringBuilder();
        foreach (string arg in args) {
            builder.Append("ARG=").AppendLine(arg);
        }
        builder.Append("CACHE=").AppendLine(Environment.GetEnvironmentVariable("GALAXY_REFACTOR_CACHE"));
        builder.Append("REPORT=").AppendLine(Environment.GetEnvironmentVariable("GALAXY_R0_REPORT_DIR"));
        File.WriteAllText(record, builder.ToString(), new UTF8Encoding(false));
        byte[] message = new UTF8Encoding(false).GetBytes("入口委托失败：验证未完成\n");
        using (Stream stdout = Console.OpenStandardOutput()) {
            stdout.Write(message, 0, message.Length);
        }
        return 23;
    }
}
""",
            encoding="utf-8-sig",
        )
        exe = temp / "bash-boundary.exe"
        compile = subprocess.run(
            [str(CSC), "/nologo", "/utf8output", f"/out:{exe}", str(source)],
            cwd=temp,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        self.assertEqual(0, compile.returncode, compile.stdout + compile.stderr)
        return exe


if __name__ == "__main__":
    unittest.main()
