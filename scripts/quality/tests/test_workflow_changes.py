"""Ejecuta la selección real de CI para detectar suites omitidas por cambios compartidos."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]
GIT_BASH = Path(r'C:\Program Files\Git\bin\bash.exe')
BASH = str(GIT_BASH) if os.name == 'nt' and GIT_BASH.is_file() else shutil.which('bash')
SUITES = {'java', 'scraper', 'semantic', 'frontend', 'contracts', 'compose',
          'linux_installer', 'download_flow', 'sonar'}


@unittest.skipUnless(BASH, 'La selección de GitHub Actions requiere Bash')
class WorkflowChangesTest(unittest.TestCase):
    """Comprueba el Bash del workflow, incluidos despacho manual y recursos de traducción."""

    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.safe_load((ROOT / '.github/workflows/quality.yml').read_text(encoding='utf-8'))
        cls.selection = next(step['run'] for step in cls.workflow['jobs']['changes']['steps']
                             if step.get('id') == 'filter')

    def selected(self, paths, event='push'):
        """Sustituye solo el listado de Git; ejecuta las decisiones originales del workflow."""
        listing = ' '.join(shlex.quote(path) for path in paths)
        stub = f"git() {{ printf '%s\\n' {listing}; }}\n" if paths else "git() { :; }\n"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'outputs'
            subprocess.run([BASH, '--noprofile', '--norc', '-c', stub + self.selection],
                           env={**os.environ, 'EVENT_NAME': event, 'BEFORE_SHA': 'before',
                                'BASE_SHA': 'base', 'HEAD_SHA': 'head',
                                'GITHUB_OUTPUT': output.as_posix()},
                           check=True, capture_output=True, text=True)
            return {key for key, value in (line.split('=', 1) for line in
                    output.read_text(encoding='utf-8').splitlines()) if value == 'true'}

    def test_shared_locale_runs_java_frontend_and_download_flow(self):
        selected = self.selected(['services/translation-service/locales/es/catalog.json'])
        self.assertTrue({'java', 'frontend', 'download_flow', 'sonar'} <= selected)

    def test_workflow_change_runs_every_suite(self):
        self.assertEqual(SUITES, self.selected(['.github/workflows/quality.yml']))

    def test_manual_dispatch_runs_every_suite_without_a_git_diff(self):
        self.assertEqual(SUITES, self.selected([], event='workflow_dispatch'))

    def test_readme_change_does_not_run_unrelated_suites(self):
        self.assertEqual(set(), self.selected(['README.md']))

    def test_feature_pull_requests_are_checked_before_development_merge(self):
        triggers = self.workflow.get('on', self.workflow.get(True))
        self.assertIn('development', triggers['pull_request']['branches'])


if __name__ == '__main__':
    unittest.main()
