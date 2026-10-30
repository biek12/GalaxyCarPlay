import importlib.util
import json
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('cluster_fields',Path(__file__).resolve().parents[1]/'capture_cluster_fields.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ClusterFieldsTest(unittest.TestCase):
    def test_only_allowed_finite_values_survive(self):
        data={'schema':1,'credential':'private','fields':{'vin':{'value':'private'},'displaySpeed':{'value':float('nan'),'status':'VALID'},'cruiseState':{'value':'active','status':'VALID','ageMillis':200},'electricPowerRaw':{'value':18.5,'status':'VALID'}}}
        result=module.parse('private output\nCLUSTER_FIELDS='+json.dumps(data)+'\n')
        self.assertNotIn('private',json.dumps(result));self.assertIsNone(result['fields']['displaySpeed']['value'])
        self.assertEqual(18.5,result['fields']['electricPowerRaw']['value'])
        self.assertEqual('active',result['fields']['cruiseState']['value'])
    def test_missing_or_wrong_contract_fails_without_recording_raw_output(self):
        for raw in ('secret', 'CLUSTER_FIELDS={"schema":2}'):
            with self.assertRaises(ValueError):module.parse(raw)
