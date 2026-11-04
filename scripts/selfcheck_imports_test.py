"""Self-isolated checks for imports supplied by a non-gateway runtime.

Run with the Hermes managed Python. No live gateway, device, or database is used.
"""
import atexit
import os
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
# Its own Hermes root outside ~/.hermes; may relaunch once into Hermes' dependency environment.
from isolated_home import enter  # noqa: E402
HOME = enter('selfcheck-imports-')
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from mobile import selfcheck as SC


class RuntimeImports(unittest.TestCase):
    def test_current_gateway_import_audit(self):
        self.assertEqual(SC.import_problems(), [])

    def problems(self, source, filename='kitty_terminal.py'):
        with tempfile.TemporaryDirectory(dir=HOME.name) as directory:
            root = Path(directory)
            (root / filename).write_text(source)
            resolve = SC._resolve
            def gateway_only(module, name):
                if module.startswith(('kitty.', 'kittens.')):
                    raise ModuleNotFoundError('Kitty SDK is only present inside Kitty')
                return resolve(module, name)
            with patch.object(SC, '_resolve', side_effect=gateway_only):
                return SC.import_problems(root)

    def test_known_kitty_sdk_is_not_a_gateway_dependency(self):
        source = ('from kittens.tui.handler import result_handler\n'
                  'from kitty.fast_data_types import PRESS, RELEASE, send_mouse_event\n')
        self.assertEqual(self.problems(source), [])

    def test_exception_is_limited_to_the_fixed_kitty_helper(self):
        result = self.problems('from kitty.fast_data_types import PRESS\n', 'adapter.py')
        self.assertEqual([p['name'] for p in result], ['kitty.fast_data_types.PRESS'])

    def test_unknown_kitty_symbol_still_fails(self):
        result = self.problems('from kitty.fast_data_types import unknown_sdk_symbol\n')
        self.assertEqual([p['name'] for p in result], ['kitty.fast_data_types.unknown_sdk_symbol'])

    def test_normal_import_errors_are_not_hidden(self):
        result = self.problems('from pathlib import NotARealPathClass\n')
        self.assertEqual([p['name'] for p in result], ['pathlib.NotARealPathClass'])

    def test_helper_syntax_is_still_checked(self):
        self.assertEqual(self.problems('def invalid(\n')[0]['name'], 'kitty_terminal.py')


if __name__ == '__main__':
    try:
        unittest.main(verbosity=2)
    finally:
        HOME.cleanup()
