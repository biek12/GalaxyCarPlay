"""只读性能输出解析：只保留自有进程计数，不保留其它进程或原始命令输出。"""
from pathlib import Path
import importlib.util
import unittest

spec = importlib.util.spec_from_file_location('auto_measure', Path(__file__).resolve().parents[1] / 'measure_autoservice.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class AutoServiceMeasureTest(unittest.TestCase):
    def test_parentheses_in_process_name_do_not_shift_cpu_counters(self):
        fields = ['S'] + ['0'] * 21
        fields[11], fields[12], fields[19] = '14', '6', '234'
        data = 'BEGIN autoservice-root 12\n12 (root (owned)) ' + ' '.join(fields) + '\nVmRSS: 1024 kB\nThreads: 4\nPss: 300 kB\nFD: 9\nEND\n'
        row = module.parse(data)[0]
        self.assertEqual(row['cpu_ticks'], 20)
        self.assertEqual(row['started_ticks'], 234)
        self.assertEqual(row['Pss'], 300)
        self.assertEqual(row['FD'], 9)
    def test_unrelated_processes_and_raw_values_are_not_recorded(self):
        self.assertEqual([], module.parse('BEGIN other.application 10\nsecret=value\nEND\n'))
        row = module.parse('BEGIN com.ecarx.autoservice 11\ncredential=private\nVmRSS: 20 kB\nEND\n')[0]
        self.assertNotIn('credential', row)
        self.assertEqual(row['VmRSS'], 20)

    def test_projection_dump_keeps_only_numeric_owned_metrics(self):
        text = 'other private=12\nhab=rootTransport=false captured=20 newFramesSubmitted=18 composeUsP95=3000 postIntervalUsP99=44000\nmedia title=private\n'
        metrics = module.projection_metrics(text)
        self.assertEqual(metrics, {'captured': 20, 'newFramesSubmitted': 18, 'composeUsP95': 3000, 'postIntervalUsP99': 44000})
        self.assertEqual({}, module.projection_metrics('media title=private'))

if __name__ == '__main__':
    unittest.main()
