"""Reutiliza una validación de PR solo para el mismo árbol integrado en main."""
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
from zipfile import BadZipFile, ZipFile

ARTIFACT_NAME = 'quality-tree-main'
TREE_FILE = 'validated-tree.txt'


def github_get(endpoint):
    """Usa la autenticación de gh, también al seguir la descarga del artefacto."""
    return subprocess.run(['gh', 'api', endpoint], check=True, capture_output=True,
                          timeout=30).stdout


def reusable_validation(repository, commit, tree, get=github_get):
    """Devuelve la URL de la ejecución aprobada o None para ejecutar las suites."""
    prefix = f'repos/{repository}'
    try:
        pulls = json.loads(get(f'{prefix}/commits/{commit}/pulls?per_page=100'))
        for pull in pulls:
            if not (pull.get('merged_at') and pull.get('merge_commit_sha') == commit
                    and pull['base']['ref'] == 'main'
                    and pull['base']['repo']['full_name'] == repository):
                continue
            head = pull['head']['sha']
            runs = json.loads(get(f'{prefix}/actions/workflows/quality.yml/runs'
                                  f'?event=pull_request&head_sha={head}&per_page=100'))['workflow_runs']
            if not runs:
                continue
            latest = runs[0]
            if (latest['status'] != 'completed' or latest['conclusion'] != 'success'
                    or latest['head_sha'] != head or latest['event'] != 'pull_request'):
                continue
            artifacts = json.loads(get(f'{prefix}/actions/runs/{latest["id"]}/artifacts'
                                       '?per_page=100'))['artifacts']
            for artifact in artifacts:
                if (artifact['name'] != ARTIFACT_NAME or artifact['expired']
                        or artifact['size_in_bytes'] > 65_536):
                    continue
                payload = get(f'{prefix}/actions/artifacts/{artifact["id"]}/zip')
                with ZipFile(BytesIO(payload)) as archive:
                    if archive.getinfo(TREE_FILE).file_size > 64:
                        continue
                    validated_tree = archive.read(TREE_FILE).decode('ascii').strip()
                if validated_tree == tree:
                    return latest['html_url']
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError, RuntimeError,
            BadZipFile):
        # Una consulta fallida nunca debe permitir omitir las pruebas.
        return None
    return None


def main():
    """Publica la decisión del push de main; el resto de eventos conserva sus pruebas."""
    validation = None
    if (os.environ.get('GITHUB_EVENT_NAME') == 'push'
            and os.environ.get('GITHUB_REF') == 'refs/heads/main'
            and os.environ.get('GITHUB_RUN_ATTEMPT', '1') == '1'):
        tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
        validation = reusable_validation(os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA'], tree)
    with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as output:
        output.write(f'reused={str(validation is not None).lower()}\n')
    if validation:
        print(f'Pruebas ya aprobadas para este mismo árbol: {validation}')
    else:
        print('Sin una validación reutilizable; se ejecutarán las suites habituales.')


if __name__ == '__main__':
    main()
