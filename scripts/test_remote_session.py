import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from remote_car import resolve_session

class SessionSelectionTest(unittest.TestCase):
    def test_latest_pairing_for_same_device(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True):
            root=Path(tmp)
            old=root/'remote-session.json';new=root/'remote-session-20261105.json'
            for path in (old,new):path.write_text(json.dumps(dict(device='a',token='test-only',origin='https://example.test',until=time.time()+3600)))
            os.utime(old,(1,1));os.utime(new,(2,2))
            self.assertEqual(resolve_session(directory=root),new)
    def test_explicit_environment_is_respected(self):
        with patch.dict(os.environ,{'GALAXY_REMOTE_SESSION':'private-selected.json'}):
            self.assertEqual(resolve_session(),Path('private-selected.json'))
            self.assertEqual(resolve_session('explicit.json'),Path('explicit.json'))
    def test_multiple_devices_require_explicit_selection(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ,{},clear=True):
            root=Path(tmp)
            for i in range(2):(root/f'remote-session-{i}.json').write_text(json.dumps(dict(device=str(i),token='test-only',origin='https://example.test',until=time.time()+3600)))
            with self.assertRaises(ValueError):resolve_session(directory=root)
if __name__=='__main__':unittest.main()
