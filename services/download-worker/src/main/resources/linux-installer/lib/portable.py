"""Prepara artefactos portables en un directorio temporal antes de activarlos.

El coordinador mantiene el diario, la publicación atómica y la limpieza del directorio.
Este módulo comparte las reglas de formato sin decidir alcance ni propiedad del programa.
"""
import shlex
import shutil

from data import child, detect, elf_arch, extract, require


def prepare_portable(component, payload, stage, destination, mode, architecture=None, encoding=None):
    """Prepara el contenido y su lanzador, sin activar la instalación.

    Args:
        component: Receta validada; tarball debe declarar un entrypoint contenido en el archivo.
        payload: Instalador cuyo formato e integridad ya comprobó el coordinador.
        stage: Directorio temporal vacío sobre el mismo sistema de archivos del destino.
        destination: Ruta definitiva que utilizará el lanzador tras publicar el directorio.
        mode: Permisos del lanzador: 0700 para usuario o 0755 para sistema.
        architecture: Arquitectura esperada; None la detecta al comprobar un binario ELF.
        encoding: Codificación del lanzador; None conserva la predeterminada del runtime.

    Raises:
        ValueError: Si falta el entrypoint, la arquitectura no coincide o Java no está disponible.
        OSError: Si falla la copia, extracción, escritura o cambio de permisos.

    See Also:
        runtime.Installer.portable: Persiste metadatos privados y activa la instalación de usuario.
        system.system_portable: Publica el programa y registra sus propietarios del sistema.
    """
    target = stage / "payload"
    strategy = component["profile"]["strategy"]
    if strategy == "tarball":
        target.mkdir()
        extract(payload, target)
        entry = child(target, component["profile"]["entrypoint"])
        require(entry.is_file(), "recipe_entrypoint_missing")
        if arch := elf_arch(entry):
            require(arch == (detect()["architecture"] if architecture is None else architecture),
                    "binary_architecture_mismatch")
        entry.chmod(entry.stat().st_mode | (mode & 0o111))
        command = [str(destination / "payload" / component["profile"]["entrypoint"])]
    else:
        shutil.copyfile(payload, target)
        target.chmod(mode if strategy == "appimage" else mode & ~0o111)
        if strategy == "appimage":
            require(elf_arch(target) == (detect()["architecture"] if architecture is None else architecture),
                    "binary_architecture_mismatch")
            command = [str(destination / "payload")]
        else:
            require(shutil.which("java"), "java_required")
            command = ["java", "-jar", str(destination / "payload")]
    (stage / "launch").write_text("#!/usr/bin/env bash\nexec " + shlex.join(command) + ' "$@"\n',
                                 encoding=encoding)
    (stage / "launch").chmod(mode)
