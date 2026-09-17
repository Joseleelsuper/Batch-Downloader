# Batch Linux Installer

Extrae **todo** el ZIP y ejecuta `bash install.sh` en una terminal. El asistente
muestra los componentes y pide confirmación; solo las operaciones del sistema
solicitan sudo. No ejecutes el asistente entero con sudo.

```bash
bash install.sh --dry-run
bash install.sh
bash update.sh
bash rollback.sh
bash uninstall.sh
bash uninstall.sh --purge
```

Después de instalar puedes borrar el ZIP. El comando conservado es
`~/.local/bin/batch-linux-installer uninstall BUNDLE_ID`; el resumen muestra
el identificador exacto. Usa `list` para consultar los bundles.

Opciones: `--silent` (acepta el plan, sudo no interactivo), `--dry-run`
(sin red ni escrituras), `--components id,id`, `--scope user|system`,
`--no-tui`, `--log-level DEBUG|INFO|WARN|ERROR`, `--purge`.
El instalador necesita Bash, Python 3.10+, util-linux (flock) y herramientas
de su gestor. Las actualizaciones necesitan curl y gpgv cuando usan firmas.
No se instala ninguna herramienta de arranque sin intervención.

DEB/RPM/Arch usan su gestor nativo; AppImage y archivos portables usan XDG.
Un tarball o JAR necesita una receta aprobada. Un formato incompatible aparece
como manual: el archivo sigue disponible en el ZIP. Las dependencias de paquetes
pueden necesitar Internet. No se convierten formatos ni se añaden repositorios.

Se comprueba SHA256 antes de instalar. Ese hash garantiza la integridad respecto
al ZIP; no equivale a una firma del fabricante. Las actualizaciones solo se
admiten con checksum publicado por el origen aprobado o firma de clave fijada.
Las firmas no se obtienen de servidores de claves automáticamente.

El estado se guarda en `~/.local/state/batch-linux-installer`. Las operaciones
nativas mantienen además un registro compartido en `/var/lib/batch-linux-installer`.
Un paquete preexistente se conserva. Las aplicaciones actualizadas externamente
se conservan y se informa del conflicto. No se borran documentos personales.
`--purge` limpia únicamente los datos administrados del bundle; conserva registros
compartidos todavía usados por otros bundles.

El rollback conserva hasta dos versiones, con un presupuesto de 2 GiB. Los
paquetes del sistema solo retroceden si hay un archivo anterior registrado.
No puede revertir efectos externos de scripts del fabricante ni cambios que
otros procesos hayan efectuado sobre el sistema. Un diario permite reintentar
la recuperación tras una interrupción.

## English

Extract the complete ZIP, then run `bash install.sh`. Use `--dry-run` to inspect
the plan. `update.sh`, `rollback.sh` and `uninstall.sh` share persistent state,
so the original ZIP is not needed afterwards. Only native/system operations use
sudo. Existing packages and personal documents are preserved. SHA256 checks the
bundle's integrity; publisher authentication requires a pinned GPG signature.
Updates require approved HTTPS origins and a published checksum or valid signature.
