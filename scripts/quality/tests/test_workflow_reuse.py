"""Contrasta el cableado de la reutilización de CI con el workflow real."""
from pathlib import Path
import unittest

import yaml


class WorkflowReuseTest(unittest.TestCase):
    def test_only_main_pushes_reuse_evidence_recorded_by_main_pull_requests(self):
        root = Path(__file__).resolve().parents[3]
        workflow = yaml.safe_load((root / '.github/workflows/quality.yml').read_text(encoding='utf-8'))
        steps = workflow['jobs']['changes']['steps']
        prior = next(step for step in steps if step.get('id') == 'prior-validation')
        self.assertEqual("github.event_name == 'push' && github.ref == 'refs/heads/main'", prior['if'])
        filter_step = next(step for step in steps if step.get('id') == 'filter')
        self.assertEqual("steps.prior-validation.outputs.reused != 'true'", filter_step['if'])
        upload = next(step for step in steps if step.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertEqual("github.event_name == 'pull_request' && github.base_ref == 'main'", upload['if'])
        self.assertEqual('quality-tree-main', upload['with']['name'])
        self.assertNotIn('actions', workflow['permissions'])
        self.assertEqual({'contents': 'read', 'pull-requests': 'read', 'actions': 'read'},
                         workflow['jobs']['changes']['permissions'])


if __name__ == '__main__':
    unittest.main()
