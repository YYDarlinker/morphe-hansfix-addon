import importlib.util,unittest
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'tools/check_addon_runtime.py'
s=importlib.util.spec_from_file_location('runtime',p);runtime=importlib.util.module_from_spec(s);s.loader.exec_module(runtime)
class RuntimeNamespaceTests(unittest.TestCase):
    def test_own_namespace(self):runtime.validate_namespace({'Lio/github/yydarlinker/hansfix/Example;'})
    def test_official_class_rejected(self):
        with self.assertRaises(ValueError):runtime.validate_namespace({'Lapp/morphe/extension/youtube/Settings;'})
    def test_stdlib_rejected(self):
        with self.assertRaises(ValueError):runtime.validate_namespace({'Lkotlin/Unit;'})
    def test_empty_rejected(self):
        with self.assertRaises(ValueError):runtime.validate_namespace(set())
    def test_invalid_dex(self):
        with self.assertRaises(ValueError):runtime.dex_classes(b'not-dex')
if __name__=='__main__':unittest.main()
