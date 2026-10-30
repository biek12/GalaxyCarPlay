"""首次准备不能以电脑ADB、旧UID证据或Telnet命令回显代替本App验证。"""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('prepare_autoservice_adb', Path(__file__).resolve().parents[1] / 'prepare_autoservice_adb.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AdbPrepareTest(unittest.TestCase):
    def evidence(self, **changes):
        result = dict(state='verified', adbUid=0, rootUid=0, tlsPort=35671,
                      verifiedAtElapsedMillis=2000, identitySource='autoservice_private_adb')
        result.update(changes)
        return 'secret preamble\nFOUNDATION_CHECK=' + json.dumps(result)

    def test_accepts_only_fresh_own_app_identity_without_raw_or_extra_fields(self):
        value = module.foundation(self.evidence(secret='fixture-do-not-record', qnx_password='fixture-do-not-record'), 1000)
        self.assertEqual(0, value['adbUid'])
        self.assertNotIn('fixture-do-not-record', json.dumps(value))
        self.assertNotIn('secret', json.dumps(value))

    def test_shell_or_helper_uid_alone_cannot_pass(self):
        for change in (dict(adbUid=2000), dict(rootUid=2000), dict(adbUid=False),
                       dict(tlsPort=0), dict(verifiedAtElapsedMillis=999), dict(identitySource='laptop_adb')):
            with self.assertRaises(ValueError):
                module.foundation(self.evidence(**change), 1000)

    def test_incomplete_or_absent_evidence_stays_pending(self):
        self.assertIsNone(module.foundation('No services match', 1000))
        self.assertIsNone(module.foundation('FOUNDATION_CHECK={"state":"checking"}', 1000))

    def test_android_dumpsys_service_indentation_does_not_hide_verified_identity(self):
        raw = 'SERVICE com.ecarx.autoservice/.AutoService fixture\n  Client:\n    ' + self.evidence().splitlines()[-1]
        self.assertEqual('autoservice_private_adb', module.foundation(raw, 1000)['identitySource'])
        with self.assertRaises(ValueError):
            module.foundation(raw, 3000)  # 缩进不影响本次触发后的新鲜度门槛。

    def test_command_echo_never_counts_as_execution(self):
        marker = 'ASPREP_fixture'
        class Channel:
            def __init__(self):
                self.socket = self
                self.chunks = []
            def sendall(self, value):
                echo = value.decode()
                self.chunks = [echo, '\r\n' + marker + '_BEGIN\r\nLinux\r\n0\r\n' + marker + '_RC:0\r\n' + marker + '_END\r\n']
            def receive(self, _):
                return self.chunks.pop(0)
        with patch.object(module.uuid, 'uuid4') as uid:
            uid.return_value.hex = 'fixture'
            self.assertEqual('Linux\n0', module.RootShell(Channel()).command('uname -s; id -u'))
