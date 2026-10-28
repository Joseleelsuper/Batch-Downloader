"""Genera el contrato de consultas anónimas para IA desde el OpenAPI principal."""

import argparse
from copy import deepcopy
import json
from pathlib import Path
from urllib.parse import urljoin

import yaml

ROOT = Path(__file__).resolve().parents[2]
PUBLIC_PATHS = (
    '/apps', '/apps/facets', '/apps/stats', '/apps/{appId}',
    '/bundles', '/bundles/catalog/facets', '/bundles/{bundleId}',
)


def references(value):
    """Recorre referencias locales para conservar solo los componentes utilizados."""
    if isinstance(value, dict):
        if '$ref' in value:
            yield value['$ref']
        for child in value.values():
            yield from references(child)
    elif isinstance(value, list):
        for child in value:
            yield from references(child)


def component(source, reference):
    """Resuelve un componente local; rechaza referencias externas o de autenticación."""
    parts = reference.split('/')
    if len(parts) != 4 or parts[:2] != ['#', 'components'] or parts[2] == 'securitySchemes':
        raise ValueError(f'Referencia no permitida en el contrato de IA: {reference}')
    section, name = parts[2:]
    return section, name, source['components'][section][name]


def build_document(source, base_url='https://batchdownloader.dev'):
    """Selecciona únicamente GET públicos revisados y sus dependencias de esquema."""
    paths = {}
    for path in PUBLIC_PATHS:
        item = source['paths'][path]
        operation = item['get']
        if (operation.get('security', source.get('security', [])) != []
                or operation.get('x-required-roles') or 'requestBody' in operation
                or 'servers' in item or 'servers' in operation):
            raise ValueError(f'La consulta de IA debe seguir siendo pública y de lectura: {path}')
        for parameter in [*item.get('parameters', []), *operation.get('parameters', [])]:
            if '$ref' in parameter:
                parameter = component(source, parameter['$ref'])[2]
            if parameter['in'] not in {'path', 'query'}:
                raise ValueError(f'La consulta de IA no admite cabeceras ni cookies: {path}')
        paths[path] = {'get': deepcopy(operation)}
        if 'parameters' in item:
            paths[path]['parameters'] = deepcopy(item['parameters'])

    paths['/bundles/{bundleId}']['get']['summary'] = 'Consultar un bundle público u oficial'
    components = {}
    pending = list(references(paths))
    seen = set()
    while pending:
        reference = pending.pop()
        if reference in seen:
            continue
        section, name, value = component(source, reference)
        components.setdefault(section, {})[name] = deepcopy(value)
        seen.add(reference)
        pending.extend(references(value))

    info = deepcopy(source['info'])
    info.update(
        title='Batch Downloader API de lectura para IA',
        description='Consultas anónimas del catálogo de aplicaciones y de bundles públicos u oficiales. '
                    'Solo lectura, sin sesión, cookies ni token CSRF.',
    )
    return {
        'openapi': source['openapi'],
        'info': info,
        'servers': [{'url': urljoin(base_url, source['servers'][0]['url'])}],
        'security': [],
        'paths': paths,
        'components': components,
    }


def main():
    """Escribe JSON UTF-8 reproducible sin mantener una segunda copia del contrato."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--base-url', default='https://batchdownloader.dev')
    args = parser.parse_args()
    source = yaml.safe_load((ROOT / 'shared/contracts/openapi/batch-downloader-api.yaml')
                            .read_text(encoding='utf-8'))
    document = build_document(source, args.base_url)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
