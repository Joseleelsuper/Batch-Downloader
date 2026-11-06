"""Evita omitir CI con validaciones fallidas, antiguas o de otro código."""
from io import BytesIO
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from zipfile import ZipFile

from scripts.quality import reuse_pr_validation as reuse

REPOSITORY = 'owner/project'
COMMIT, TREE, HEAD = 'a' * 40, 'b' * 40, 'c' * 40
RUN_URL = 'https://github.com/owner/project/actions/runs/10'


def archive(tree=TREE, filename=reuse.TREE_FILE):
    result = BytesIO()
    with ZipFile(result, 'w') as content:
        content.writestr(filename, tree + '\n')
    return result.getvalue()


class ReusePrValidationTest(unittest.TestCase):
    """Comprueba identidad del código y recuperación segura ante ausencia de evidencia."""

    def setUp(self):
        self.pull = {'merged_at': '2026-11-06T20:00:00Z', 'merge_commit_sha': COMMIT,
                     'base': {'ref': 'main', 'repo': {'full_name': REPOSITORY}},
                     'head': {'sha': HEAD}}
        self.run = {'id': 10, 'status': 'completed', 'conclusion': 'success',
                    'head_sha': HEAD, 'event': 'pull_request', 'html_url': RUN_URL}
        self.artifact = {'id': 20, 'name': reuse.ARTIFACT_NAME, 'expired': False,
                         'size_in_bytes': 300}

    def check(self, payload=None, pulls=None, runs=None, artifacts=None):
        responses = [json.dumps([self.pull] if pulls is None else pulls).encode(),
                     json.dumps({'workflow_runs': [self.run] if runs is None else runs}).encode(),
                     json.dumps({'artifacts': [self.artifact] if artifacts is None else artifacts}).encode(),
                     archive() if payload is None else payload]
        self.get = Mock(side_effect=responses)
        return reuse.reusable_validation(REPOSITORY, COMMIT, TREE, self.get)

    def test_reuses_successful_pr_only_for_the_exact_merged_tree(self):
        self.assertEqual(RUN_URL, self.check())
        self.assertEqual(4, self.get.call_count)

    def test_different_tree_requires_normal_tests(self):
        self.assertIsNone(self.check(payload=archive('d' * 40)))

    def test_commit_without_a_merged_main_pr_requires_normal_tests(self):
        for field, value in [('merged_at', None), ('merge_commit_sha', 'other')]:
            with self.subTest(field=field):
                pull = {**self.pull, field: value}
                self.assertIsNone(self.check(pulls=[pull]))
                self.assertEqual(1, self.get.call_count)
        self.assertIsNone(self.check(pulls=[]))
        pull = {**self.pull, 'base': {'ref': 'development', 'repo': {'full_name': REPOSITORY}}}
        self.assertIsNone(self.check(pulls=[pull]))

    def test_does_not_fall_back_to_an_older_success_after_a_failed_or_pending_run(self):
        for changes in [{'conclusion': 'failure'}, {'status': 'in_progress', 'conclusion': None},
                        {'head_sha': 'other'}, {'event': 'push'}]:
            with self.subTest(changes=changes):
                self.assertIsNone(self.check(runs=[{**self.run, **changes}, self.run]))
                self.assertEqual(2, self.get.call_count)
        self.assertIsNone(self.check(runs=[]))

    def test_missing_expired_or_oversized_evidence_requires_normal_tests(self):
        for artifacts in [[], [{**self.artifact, 'expired': True}],
                          [{**self.artifact, 'name': 'other'}],
                          [{**self.artifact, 'size_in_bytes': 100_000}]]:
            with self.subTest(artifacts=artifacts):
                self.assertIsNone(self.check(artifacts=artifacts))
                self.assertEqual(3, self.get.call_count)

    def test_corrupt_or_unexpected_archive_requires_normal_tests(self):
        for payload in [b'not a zip', archive(filename='other.txt'), archive('b' * 100)]:
            with self.subTest(payload=payload[:10]):
                self.assertIsNone(self.check(payload=payload))

    def test_api_failure_requires_normal_tests(self):
        self.assertIsNone(reuse.reusable_validation(REPOSITORY, COMMIT, TREE,
                                                   Mock(side_effect=OSError('offline'))))

    def test_other_events_never_skip_tests_or_query_github(self):
        for event, ref in [('pull_request', 'refs/pull/1/merge'),
                           ('push', 'refs/heads/development'), ('workflow_dispatch', 'refs/heads/main')]:
            with self.subTest(event=event, ref=ref), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'outputs'
                with patch.dict(os.environ, {'GITHUB_EVENT_NAME': event, 'GITHUB_REF': ref,
                                             'GITHUB_OUTPUT': str(output)}), \
                        patch.object(reuse, 'reusable_validation') as lookup:
                    reuse.main()
                lookup.assert_not_called()
                self.assertEqual('reused=false\n', output.read_text(encoding='utf-8'))

    def test_main_push_only_skips_when_validation_is_reusable(self):
        for validation, expected in [(RUN_URL, 'true'), (None, 'false')]:
            with self.subTest(validation=validation), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'outputs'
                with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main',
                                             'GITHUB_RUN_ATTEMPT': '1',
                                             'GITHUB_REPOSITORY': REPOSITORY, 'GITHUB_SHA': COMMIT,
                                             'GITHUB_OUTPUT': str(output)}), \
                        patch.object(reuse.subprocess, 'check_output', return_value=TREE + '\n'), \
                        patch.object(reuse, 'reusable_validation', return_value=validation) as lookup:
                    reuse.main()
                lookup.assert_called_once_with(REPOSITORY, COMMIT, TREE)
                self.assertEqual(f'reused={expected}\n', output.read_text(encoding='utf-8'))

    def test_manual_rerun_forces_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'outputs'
            with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main',
                                         'GITHUB_RUN_ATTEMPT': '2', 'GITHUB_OUTPUT': str(output)}), \
                    patch.object(reuse, 'reusable_validation') as lookup:
                reuse.main()
            lookup.assert_not_called()
            self.assertEqual('reused=false\n', output.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
