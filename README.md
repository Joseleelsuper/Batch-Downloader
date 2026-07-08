# Batch-Downloader

⠀⠀⠀
<div align="center">
    <img src="./assets/BatchDownloaderI.png" alt="Batch Downloader" width="400px"/>
</div>
⠀⠀⠀

Permite descargar varios ejecutables de programas conocidos al mismo tiempo. Ideal para poner un nuevo PC en marcha.

## Despliegue Docker

La aplicacion web se despliega con un unico `docker-compose.yml` y un archivo `.env`.
Las imagenes propias se publican en GitHub Container Registry desde la rama `main`.

```bash
cp .env.example .env
docker compose --env-file .env up -d
```

Por defecto, el compose usa imagenes como `ghcr.io/joseleelsuper/batch-downloader-webapp:main`.
Para que un usuario pueda arrancar la aplicacion sin `docker login ghcr.io`, los paquetes de GHCR deben estar marcados como publicos.

## Licencia

Este proyecto está bajo la Licencia GNU GENERAL PUBLIC LICENSE 3.0. Para más detalles, consulte el archivo [LICENSE](LICENSE).

## Personas

### Autor

<table>
    <tr>
        <td align="center">
            <a href="https://joseleelportfolio.vercel.app/">
                <img src="https://github.com/Joseleelsuper.png" width="100px;" alt="José Gallardo"/>
                <br />
                <sub><b>José Gallardo Caballero</b></sub>
            </a>
        </td>
    </tr>
</table>

### Tutores

<table>
    <tr>
        <td align="center">
            <a href="https://github.com/JoseManuelAroca">
                <img src="https://github.com/JoseManuelAroca.png" width="100px;" alt="José Manuel Aroca Fernández"/>
                <br />
                <sub><b>José Manuel Aroca Fernández</b></sub>
            </a>
        </td>
        <td align="center">
            <a href="https://github.com/RodrigoPascual">
                <img src="https://github.com/RodrigoPascual.png" width="100px;" alt="Rodrigo Pascual García"/>
                <br />
                <sub><b>Rodrigo Pascual García</b></sub>
            </a>
        </td>
    </tr>
</table>

---

> Volver al [índice](#índice)
