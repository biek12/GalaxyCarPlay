"""现场入口不能把原始诊断/认证文字带进快照，也不能误认无效dump。"""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('cluster_field_session', Path(__file__).resolve().parents[1] / 'cluster_field_session.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FieldSnapshotTest(unittest.TestCase):
    def test_only_owned_numeric_and_boolean_fields_survive(self):
        raw = ('status=private-credential\nqnxCredentialsConfigured=true\n'
               'projectionRequested=true platformReady=false preparing=true paused=false\n'
               'vehiclePropertyCount=123\nvehicleSampledCount=100\nvehicleValidCount=80\n'
               'hab=private-payload outputPosted=43 frameAgeP95=12\n'
               'phone=private-number\n')
        result = module.safe_snapshot(raw)
        self.assertNotIn('private', json.dumps(result))
        self.assertEqual(123, result['vehiclePropertyCount'])
        self.assertEqual({'outputPosted': 43, 'frameAgeP95': 12}, result['hab_numeric'])
        self.assertFalse(result['platformReady'])
        self.assertTrue(result['qnxCredentialsConfigured'])

    def test_missing_service_or_only_hab_line_cannot_look_successful(self):
        for raw in ('No services match private-text', '', 'hab=inactive'):
            with self.assertRaises(ValueError):
                module.safe_snapshot(raw)

    def test_stopped_service_is_distinct_from_missing_service(self):
        result = module.safe_snapshot('automatic=false\nhab=inactive\n')
        self.assertTrue(result['hab_inactive'])
        self.assertFalse(result['automatic'])

    def test_indented_v4_dump_keeps_only_numeric_collection_metrics(self):
        result = module.safe_snapshot('    automatic=true\n    vehicleStats=ticks=8 reads=16 bundles=8 feeds=8 scan=false active=true keys=2\n    hab=inactive\n')
        self.assertEqual({'ticks': 8, 'reads': 16, 'bundles': 8, 'feeds': 8, 'keys': 2}, result['vehicle_numeric'])
        self.assertTrue(result['automatic'])
