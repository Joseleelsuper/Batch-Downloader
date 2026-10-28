"""Verifica el límite de lectura anónima y la integridad del contrato para IA."""

import json
from pathlib import Path
import re
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]


class AiOpenApiTest(unittest.TestCase):
    """Impide que el perfil de IA incorpore escrituras, credenciales o rutas privadas."""

    @classmethod
    def setUpClass(cls):
        """Carga los dos contratos versionados sin generarlos ni modificarlos."""
        cls.source = yaml.safe_load((ROOT / 'shared/contracts/openapi/batch-downloader-api.yaml')
                                   .read_text(encoding='utf-8'))
        cls.document = json.loads((ROOT / 'shared/contracts/openapi/batch-downloader-ai-api.json')
                                  .read_text(encoding='utf-8'))

    def test_only_anonymous_catalog_reads_are_exposed(self):
        """Las siete consultas permitidas no arrastran sesiones ni operaciones auxiliares."""
        document = self.document
        self.assertEqual({
            '/apps', '/apps/facets', '/apps/stats', '/apps/{appId}',
            '/bundles', '/bundles/catalog/facets', '/bundles/{bundleId}',
        }, set(document['paths']))
        self.assertEqual([], document['security'])
        self.assertNotIn('securitySchemes', document['components'])
        self.assertEqual([{'url': 'https://batchdownloader.dev/api/v1'}], document['servers'])
        for path, item in document['paths'].items():
            with self.subTest(path=path):
                self.assertEqual({'get'}, set(item))
                self.assertEqual([], item['get']['security'])
                source = self.source['paths'][path]
                self.assertEqual([], source['get'].get('security', self.source['security']))
                self.assertFalse(source['get'].get('x-required-roles'))
                self.assertNotIn('requestBody', item['get'])
                self.assertNotIn('servers', item['get'])
                self.assertEqual(self.source['paths'][path]['get']['responses'], item['get']['responses'])
                self.assertEqual(self.source['paths'][path]['get']['operationId'], item['get']['operationId'])
                self.assertEqual(source.get('parameters', []), item.get('parameters', []))
                self.assertEqual(source['get'].get('parameters', []), item['get'].get('parameters', []))
                for parameter in [*item.get('parameters', []), *item['get'].get('parameters', [])]:
                    if '$ref' in parameter:
                        parameter = document['components']['parameters'][parameter['$ref'].rsplit('/', 1)[1]]
                    self.assertIn(parameter['in'], {'path', 'query'})
        serialized = json.dumps(document)
        for credential in ('BATCH_SESSION', 'BATCH_DOWNLOAD_OWNER', 'CsrfToken', 'X-Internal-Service-Token'):
            self.assertNotIn(credential, serialized)

    def test_components_are_complete_and_exclude_unrelated_contracts(self):
        """El JSON es autónomo y no incluye esquemas de trabajos, cuentas o administración."""
        document = self.document
        for reference in re.findall(r'"\$ref": "([^"]+)"', json.dumps(document)):
            value = document
            for part in reference.removeprefix('#/').split('/'):
                value = value[part]
        for schema in ('DownloadJob', 'User', 'CreateDownloadJobRequest', 'UpsertBundleRequest'):
            self.assertNotIn(schema, document['components']['schemas'])
        for section, values in document['components'].items():
            for name, value in values.items():
                self.assertEqual(self.source['components'][section][name], value)


if __name__ == '__main__':
    unittest.main()
