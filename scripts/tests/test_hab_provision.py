"""本地假的Telnet通道：应用key只在内存中传递，任何失败均不启动接收程序。"""
import base64
import importlib.util
import json
import subprocess
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("hab_telnet", Path(__file__).resolve().parents[2] / "tools/hab/telnet_session.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class Channel:
    def __init__(self, encoded, back=None):
        self.socket = self
        self.sent = []
        self.redactions = []
        self.responses = ["\r\nGALAXY_KEY_BEGIN\r\n" + encoded + "\r\nGALAXY_KEY_END\r\n",
                          "\r\nGALAXY_KEY_STAGED\r\n",
                          "\r\nGALAXY_KEY_WRITTEN\r\n",
                          "\r\nGALAXY_VERIFY_BEGIN\r\n" + subprocess.run(["cksum"], input=base64.b64decode(back or encoded),
                              capture_output=True, check=True).stdout.decode() + "\r\nGALAXY_VERIFY_END\r\n"]
    def sendall(self, data):
        self.sent.append(data)
    def receive(self, *args, **kwargs):
        return self.responses.pop(0)

class HabProvisionTest(unittest.TestCase):
    request = dict(qnx_destination="/var/data/galaxy-hab-test-123/app-owner.key", qnx_auth_apk="/explicit/external.apk")
    def test_success_reports_only_metadata_and_round_trips_in_memory(self):
        encoded = base64.b64encode(bytes(range(32))).decode()
        channel = Channel(encoded)
        with patch.object(MODULE, "qnx_login") as login:
            result = MODULE.provision_app_hab(channel, self.request)
        login.assert_called_once_with(channel, "/explicit/external.apk")
        self.assertTrue(result["app_hab_key_provisioned"])
        self.assertFalse(result["receiver_started"])
        self.assertNotIn(encoded, json.dumps(result))
        self.assertIn(encoded, channel.redactions)
        self.assertFalse(channel.responses)
    def test_autoservice_private_configuration_never_returns_credentials(self):
        channel = Channel(base64.b64encode(bytes(32)).decode())
        channel.responses = ["\r\nAUTOSERVICE_QNX_CONFIGURED\r\n"]
        with patch.object(MODULE, "authorized_qnx_credentials", return_value=("fixture", "synthetic-password")):
            result = MODULE.provision_autoservice_qnx(channel, "/explicit/authorized.apk")
        self.assertTrue(result["autoservice_qnx_configured"])
        self.assertNotIn("synthetic-password", json.dumps(result))
        self.assertIn(b"run-as com.ecarx.autoservice", channel.sent[0])
        self.assertIn("synthetic-password", channel.redactions)

    def test_invalid_source_never_logs_in_or_writes_key(self):
        channel = Channel(base64.b64encode(b"short").decode())
        with patch.object(MODULE, "qnx_login") as login:
            with self.assertRaises(ValueError):
                MODULE.provision_app_hab(channel, self.request)
        login.assert_not_called()
        self.assertEqual(len(channel.sent), 1)
    def test_mismatch_and_non_product_destination_fail(self):
        channel = Channel(base64.b64encode(bytes(32)).decode(), base64.b64encode(bytes([1]) * 32).decode())
        with patch.object(MODULE, "qnx_login"):
            with self.assertRaisesRegex(ValueError, "不一致"):
                MODULE.provision_app_hab(channel, self.request)
        channel = Channel("")
        with self.assertRaises(ValueError):
            MODULE.provision_app_hab(channel, dict(self.request, qnx_destination="/etc/key"))
        self.assertFalse(channel.sent)

if __name__ == "__main__":
    unittest.main()
