import json
import os
import unittest
from unittest.mock import patch, MagicMock
import seedhost_file_witness as witness

class FileWitnessTest(unittest.TestCase):
    def page(self, page, parent='/downloads'):
        response = MagicMock()
        response.status = 200
        response.url = witness.BASE + 'plugins/_getdir/listdir.php?dir=%2Fdownloads'
        response.read.return_value = json.dumps(page).encode()
        response.__enter__.return_value = response
        client = MagicMock()
        client.open.return_value = response
        with patch.dict(os.environ, {'CHUNKS_LIVE_RTORRENT_USER':'test', 'CHUNKS_LIVE_RTORRENT_PASS':'test'}), patch.object(witness.request, 'build_opener', return_value=client):
            return witness.read_parent(parent)
    def test_exact_parent_child_entries_and_canary(self):
        self.assertEqual({'/downloads/', '/downloads/target.bin', '/downloads/canary.bin', '/downloads/sub/'},
            self.page({'path':'/downloads/', 'files':['target.bin','canary.bin'], 'directories':['..','sub']}))
    def test_fallback_or_path_escape_never_proves_absence(self):
        for page in ({'path':'/', 'files':[], 'directories':[]},
                     {'path':'/downloads/', 'files':['../canary'], 'directories':[]},
                     {'path':'/downloads/', 'files':['bad\nname'], 'directories':[]}):
            with self.assertRaises(ValueError): self.page(page)
    def test_invalid_parent_refused_before_network(self):
        for parent in ('/', '/downloads/../', '/downloads//a', '/downloads\n'):
            with self.assertRaises(ValueError): witness.read_parent(parent)
    def test_redirects_are_disabled(self):
        self.assertIsNone(witness.NoRedirect().redirect_request(None,None,None,None,None,None))

if __name__ == '__main__': unittest.main()
