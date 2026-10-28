"""Verifica el límite de lectura anónima y la integridad del contrato para IA."""

from copy import deepcopy
import json
from pathlib import Path
import re
import unittest

import yaml

from scripts.quality.generate_ai_openapi import build_document

ROOT = Path(__file__).resolve().parents[3]


class AiOpenApiTest(unittest.TestCase):
    """Impide que el perfil de IA incorpore escrituras, credenciales o rutas privadas."""

    @classmethod
    def setUpClass(cls):
        """Carga el contrato canónico usado también al construir la imagen web."""
        cls.source = yaml.safe_load((ROOT / 'shared/contracts/openapi/batch-downloader-api.yaml')
                                   .read_text(encoding='utf-8'))

    def test_only_anonymous_catalog_reads_are_exposed(self):
        """Las siete consultas permitidas no arrastran sesiones ni operaciones auxiliares."""
        source = deepcopy(self.source)
        source['paths']['/apps']['post'] = {'operationId': 'futureWrite'}
        source['paths']['/other-public-read'] = {'get': {'security': []}}
        document = build_document(source)
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
                self.assertNotIn('requestBody', item['get'])
                self.assertEqual(self.source['paths'][path]['get']['responses'], item['get']['responses'])
                self.assertEqual(self.source['paths'][path]['get']['operationId'], item['get']['operationId'])
        serialized = json.dumps(document)
        for credential in ('BATCH_SESSION', 'BATCH_DOWNLOAD_OWNER', 'CsrfToken', 'X-Internal-Service-Token'):
            self.assertNotIn(credential, serialized)

    def test_generation_fails_if_a_selected_read_requires_authentication(self):
        """Un cambio de acceso explícito o heredado impide publicar una consulta como anónima."""
        for security in (None, [{'sessionCookie': []}], [{'internalServiceToken': []}]):
            with self.subTest(security=security):
                source = deepcopy(self.source)
                operation = source['paths']['/apps']['get']
                operation.pop('security')
                if security is not None:
                    operation['security'] = security
                with self.assertRaisesRegex(ValueError, 'pública y de lectura'):
                    build_document(source)

    def test_cookie_and_header_parameters_are_rejected_including_references(self):
        """No basta con security vacío si se solicitan credenciales como parámetros."""
        for parameter in ({'name': 'owner', 'in': 'cookie'}, {'$ref': '#/components/parameters/CsrfToken'}):
            with self.subTest(parameter=parameter):
                source = deepcopy(self.source)
                source['paths']['/apps']['parameters'] = [parameter]
                with self.assertRaisesRegex(ValueError, 'cabeceras ni cookies'):
                    build_document(source)

    def test_components_are_complete_and_exclude_unrelated_contracts(self):
        """El JSON es autónomo y no incluye esquemas de trabajos, cuentas o administración."""
        original = deepcopy(self.source)
        document = build_document(self.source)
        self.assertEqual(original, self.source)
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
