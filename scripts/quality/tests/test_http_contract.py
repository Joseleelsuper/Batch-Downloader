"""Contrasta el contrato HTTP con firmas Java, rutas del cliente y política de acceso.

El índice usa el parser del JDK y no carga Spring ni arranca servicios. La comprobación
de DTO compara propiedades de records que tienen un esquema equivalente declarado.
"""
import ast
import json
from fnmatch import fnmatchcase
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
ALIASES = {'IdentityView':'User', 'BundleDetails':'Bundle', 'BundleSearchResponse':'BundlePage',
           'AppSearchResponse':'AppPage', 'AppListItem':'App', 'DownloadJobView':'DownloadJob'}


def normalized(path):
    """Iguala nombres de variables de ruta sin confundir segmentos literales distintos."""
    return re.sub(r'\{[^}]+\}', '{}', path.rstrip('/'))


class HttpContractTest(unittest.TestCase):
    """Detecta rutas ausentes, bases incorrectas, DTO divergentes y mutaciones sin CSRF."""

    @classmethod
    def setUpClass(cls):
        """Parsea fuentes propias y el YAML una vez; conserva su ruta para diagnosticar fallos."""
        cls.spec = yaml.safe_load((ROOT / 'shared/contracts/openapi/batch-downloader-api.yaml').read_text(encoding='utf-8'))
        security = (ROOT / 'services/core-api/src/main/java/es/ubu/batchdownloader/identity/infrastructure/security/SecurityConfig.java').read_text(encoding='utf-8')
        cls.access_rules = []
        for matcher, policy, value in re.findall(r'\.requestMatchers\((.*?)\)\.(permitAll|hasRole)\((.*?)\)', security, re.S):
            verb = re.search(r'HttpMethod\.(\w+)', matcher)
            for pattern in re.findall(r'"([^"]+)"', matcher):
                cls.access_rules.append((verb[1].lower() if verb else None, pattern,
                                         [value.strip('"')] if policy == 'hasRole' else []))
        paths = sorted((ROOT / 'services/core-api/src/main/java').rglob('*.java'))
        paths += sorted((ROOT / 'services/translation-service/src/main/java').rglob('*.java'))
        with tempfile.TemporaryDirectory() as temporary:
            listing = Path(temporary) / 'sources.txt'
            listing.write_bytes(('\n'.join(map(str, paths))+'\n').encode('utf-8'))
            result = subprocess.run(['java', '-Dstdout.encoding=UTF-8',
                                     str(ROOT / 'scripts/quality/JavaSourceIndex.java'), str(listing)],
                                    capture_output=True, text=True, encoding='utf-8', check=True)
        cls.units = [json.loads(line) for line in result.stdout.splitlines()]
        cls.routes, cls.records = {}, {}
        for unit in cls.units:
            bases = {}
            for symbol in unit['symbols']:
                if symbol['kind'] in {'CLASS', 'RECORD', 'INTERFACE'}:
                    for annotation in symbol['annotations']:
                        if annotation.startswith('@RequestMapping'):
                            bases[symbol['name']] = re.search(r'"([^"]*)"', annotation)[1]
                if symbol['kind'] == 'RECORD':
                    owner = '.'.join(filter(None, [symbol['owner'], symbol['name']]))
                    cls.records[symbol['name']] = {
                        s['name'] for s in unit['symbols'] if s.get('recordComponent') and s['owner'] == owner}
                if symbol['kind'] != 'METHOD': continue
                for annotation in symbol['annotations']:
                    match = re.match(r'@(Get|Post|Put|Patch|Delete)Mapping', annotation)
                    if not match: continue
                    suffix = re.search(r'"([^"]*)"', annotation)
                    route = bases.get(symbol['owner'], '') + (suffix[1] if suffix else '')
                    if route.startswith(('/api/v1/', '/internal/v1/download-jobs/')):
                        cls.routes[(match[1].lower(), normalized(route))] = symbol
        cls.operations = {}
        for path, item in cls.spec['paths'].items():
            if item.get('x-service') == 'scraper': continue
            for method in ('get','post','put','patch','delete'):
                if method not in item: continue
                operation = item[method]
                servers = operation.get('servers', item.get('servers', cls.spec['servers']))
                for server in servers:
                    route = server['url'].rstrip('/')+path
                    cls.operations[(method, normalized(route))] = (item, operation)

    def test_effective_routes_match_controllers(self):
        """Cada verbo y ruta efectivos existen en ambos lados, incluido el prefijo del servidor."""
        self.assertEqual(set(self.routes), set(self.operations))

    def test_scraper_size_probe_matches_python_route_and_model(self):
        """Contrasta el probe documentado con FastAPI sin importar ni iniciar el scraper."""
        routes = ast.parse((ROOT / 'api/scraper/app/api/internal_routes.py').read_text(encoding='utf-8'))
        handler = next(node for node in routes.body if isinstance(node, ast.AsyncFunctionDef)
                       and node.name == 'get_source_size')
        decorator = next(node for node in handler.decorator_list if isinstance(node, ast.Call)
                         and isinstance(node.func, ast.Attribute) and node.func.attr == 'get')
        router = next(node.value for node in routes.body if isinstance(node, ast.Assign)
                      and any(isinstance(target, ast.Name) and target.id == decorator.func.value.id
                              for target in node.targets))
        prefix = next(keyword.value.value for keyword in router.keywords if keyword.arg == 'prefix')
        path = prefix + decorator.args[0].value
        documented = [(route, item) for route, item in self.spec['paths'].items()
                      if item.get('x-service') == 'scraper']
        self.assertEqual([normalized(path)], [normalized(route) for route, _ in documented])
        operation = documented[0][1]['get']
        self.assertEqual([{'internalServiceToken': []}], operation['security'])
        self.assertTrue(any(isinstance(node, ast.Name) and node.id == 'require_internal_service_token'
                            for argument in handler.args.args if argument.annotation
                            for node in ast.walk(argument.annotation)))
        declared_responses = ast.literal_eval(next(keyword.value for keyword in decorator.keywords
                                                  if keyword.arg == 'responses'))
        self.assertTrue({str(status) for status in declared_responses}.issubset(operation['responses']))
        model_name = next(keyword.value.id for keyword in decorator.keywords if keyword.arg == 'response_model')
        self.assertEqual(f'#/components/schemas/{model_name}',
                         operation['responses']['200']['content']['application/json']['schema']['$ref'])
        models = ast.parse((ROOT / 'api/scraper/app/schemas/internal.py').read_text(encoding='utf-8'))
        model = next(node for node in models.body if isinstance(node, ast.ClassDef) and node.name == model_name)
        aliases = {keyword.value.value for node in model.body if isinstance(node, ast.AnnAssign)
                   and isinstance(node.value, ast.Call) for keyword in node.value.keywords if keyword.arg == 'alias'}
        self.assertEqual(aliases, set(self.spec['components']['schemas'][model_name]['properties']))

    def test_declared_status_and_record_properties(self):
        """Los códigos anotados y propiedades de records conservan su forma pública documentada."""
        schemas = self.spec['components']['schemas']
        compared = set()
        for route, symbol in self.routes.items():
            with self.subTest(route=route):
                operation = self.operations[route][1]
                for annotation in symbol['annotations']:
                    if annotation.startswith('@ResponseStatus'):
                        code = next((code for name,code in {'CREATED':'201','ACCEPTED':'202','NO_CONTENT':'204'}.items() if name in annotation), None)
                        if code: self.assertIn(code, operation['responses'])
                types = [symbol['returns'], *(p['type'] for p in symbol['parameters'])]
                for value in types:
                    for name in re.findall(r'\b\w+\b', value or ''):
                        schema = ALIASES.get(name, name)
                        if name in self.records and schema in schemas and 'properties' in schemas[schema]:
                            self.assertEqual(self.records[name], set(schemas[schema]['properties']), name)
                            compared.add(name)
        self.assertGreater(len(compared), 20, 'La comprobación debe cubrir los DTO HTTP, no solo las rutas.')

    def test_access_roles_and_csrf(self):
        """Las rutas protegidas declaran sus roles y cada mutación del navegador documenta CSRF."""
        for (method, route), (item, operation) in self.operations.items():
            with self.subTest(method=method, route=route):
                expected = next((roles for verb, pattern, roles in self.access_rules
                                 if (verb is None or verb == method) and fnmatchcase(route, pattern)), [])
                self.assertEqual(expected, operation.get('x-required-roles', []))
                if expected:
                    self.assertIn({'sessionCookie': []}, operation.get('security', self.spec['security']))
                if method != 'get' and not route.startswith('/internal/'):
                    self.assertIn({'$ref':'#/components/parameters/CsrfToken'},
                                  [*item.get('parameters', []), *operation.get('parameters', [])])

    def test_frontend_literal_routes_exist(self):
        """Las rutas HTTP construidas por los clientes tienen un destino declarado; WebSocket usa otro contrato."""
        known = {route for _, route in self.operations}
        checked = set()
        for path in (ROOT / 'services/webapp/src/main/resources/frontend/src/api').glob('*.ts'):
            if path.name.endswith('.test.ts'): continue
            source = path.read_text(encoding='utf-8')
            for match in re.finditer(r'[\x27"`]([^\x27"`\n]*?/api/v1/[^\x27"`\n]*)[\x27"`]', source):
                route = match[1][match[1].index('/api/v1/'):]
                route = re.sub(r'\$\{[^}]+\}', '{}', route).split('?')[0]
                if route.endswith('/ws'): continue
                with self.subTest(client=path.name, route=route):
                    self.assertIn(normalized(route), known)
                checked.add(route)
        self.assertGreater(len(checked), 30)


if __name__ == '__main__':
    unittest.main()
