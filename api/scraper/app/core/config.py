"""Carga configuración SCRAPER_ y proporciona una instancia validada compartida por el proceso."""
import os
from enum import StrEnum
from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import URL, make_url


class GroqDescriptionModel(StrEnum):
    """Restringe los identificadores de modelos alternativos admitidos para enriquecer
    descripciones con Groq.
    """
    
    GPT_OSS_120B = "openai/gpt-oss-120b"

    QWEN_3_32B = "qwen/qwen3-32b"

    QWEN_3_6_27B = "qwen/qwen3.6-27b"

    QWEN_3_8_27B = "qwen/qwen3.8-27b"

    LLAMA_4_SCOUT = "meta-llama/llama-4-scout-17b-16e-instruct"

    LLAMA_3_1_8B = "llama-3.1-8b-instant"



DEFAULT_GROQ_DESCRIPTION_FALLBACKS = (
    GroqDescriptionModel.QWEN_3_8_27B,
)



class Settings(BaseSettings):
    """Enlaza variables SCRAPER_ y el archivo .env con límites, proveedores y persistencia del
    scraper.
    Rechaza el prefijo histórico SCRAPPER_ para evitar que una configuración antigua active
    valores predeterminados silenciosamente.

    Attributes:
        app_name: Título de FastAPI e identificación del servicio en salud.
        database_host, database_port, database_name: Destino MySQL y nombre del esquema; el
            puerto predeterminado es 3306.
        database_username, database_password: Identidad y secreto de conexión, con contraseña
            protegida por SecretStr.
        database_pool_max, database_max_overflow: Conexiones persistentes y margen de
            conexiones temporales por proceso.
        database_pool_timeout_seconds, database_pool_recycle_seconds: Espera de adquisición y
            antigüedad de reciclado de conexiones, en segundos.
        database_url_override: URL completa reservada a pruebas; si está presente sustituye
            los componentes de conexión.
        winstall_base_url, winstall_api_base_url: Bases de navegación y API del proveedor de
            catálogo Winstall.
        request_timeout_seconds, max_redirects: Timeout HTTP en segundos y máximo de saltos
            permitidos por las consultas externas.
        max_download_size_bytes, icon_max_bytes, manual_page_max_bytes: Límites de bytes para
            instalador, icono y HTML de inspección, respectivamente.
        manual_inspection_ttl_hours, manual_inspection_max_attempts: Vigencia de inspecciones
            en horas y máximo de intentos persistidos.
        so_filter_concurrency, so_filter_max_attempts: Paralelismo del filtro de plataformas y
            máximo de intentos de sus tareas.
        scrape_concurrency, scrape_app_timeout_seconds: Concurrencia del pipeline y tiempo
            máximo por aplicación, en segundos.
        scrape_max_apps: Máximo de aplicaciones por ejecución; cero no aplica este límite.
        scrape_searcher_backpressure_limit, scrape_searcher_backpressure_sleep_seconds: Umbral
            de trabajo pendiente y pausa en segundos antes de seguir descubriendo
            aplicaciones.
        cpu_thread_workers: Tamaño solicitado para el pool de cálculo; su construcción
            garantiza al menos un hilo.
        scheduler_timezone, scheduler_hour, scheduler_minute: Zona IANA y hora local de la
            ejecución diaria.
        run_on_startup: Activa una ejecución al iniciar el scheduler cuando es true.
        worker_heartbeat_interval_seconds, worker_heartbeat_stale_seconds: Cadencia del latido
            y máxima antigüedad aceptada, en segundos.
        worker_failure_threshold: Fallos consecutivos que hacen que salud considere degradado
            al worker.
        url_protection_secret: Secreto estable del despliegue para cifrar y recuperar URL
            privadas de fuentes.
        allowed_download_schemes: Esquemas admitidos al validar candidatos; el valor
            predeterminado solo permite HTTPS.
        playwright_timeout_ms: Tiempo máximo de navegación del respaldo con navegador, en
            milisegundos.
        internal_service_token: Secreto de las rutas internas; vacío nunca autoriza
            peticiones.
        llm_groq_api_key, llm_deepseek_api_key: Credenciales opcionales de los proveedores de
            descripciones.
        llm_groq_base_url, llm_deepseek_base_url: Bases de las API compatibles con chat
            completions.
        llm_groq_model, llm_groq_fallback_models, llm_deepseek_model: Modelos principal,
            alternativos de Groq y de DeepSeek utilizados por el enriquecimiento.
        llm_max_concurrency, llm_max_apps_per_run: Paralelismo de enriquecimiento y máximo por
            ejecución; cero en el segundo no limita aplicaciones.
        llm_request_timeout_seconds: Espera máxima de respuesta del proveedor, en segundos.
        llm_rate_limit_cooldown_seconds, llm_transient_cooldown_seconds,
            llm_model_error_cooldown_seconds: Pausas en segundos tras cuota agotada, fallo
            transitorio y error de modelo, respectivamente.

    See Also:
        app.db.session: Construye el pool por proceso con esta configuración.
        app.worker: Programa las ejecuciones y publica latidos.
        app.scraper.description_enricher: Consume límites y proveedores de generación de
            descripciones.
    """

    model_config = SettingsConfigDict(env_file=".env", env_prefix="SCRAPER_", extra="ignore")


    def model_post_init(self, __context: object) -> None:
        """Detecta variables con el prefijo retirado y detiene la configuración antes de arrancar
        el servicio.

        Args:
            __context: Contexto de inicialización de Pydantic, sin uso en la validación del
                prefijo.

        Raises:
            ValueError: Si existe alguna variable de entorno SCRAPPER_.
        """
        legacy_names = sorted(name for name in os.environ if name.startswith("SCRAPPER_"))
        if legacy_names:
            joined = ", ".join(legacy_names)
            raise ValueError(
                "Configuración obsoleta detectada: "
                f"{joined}. Renombra todas las variables SCRAPPER_* a SCRAPER_*."
            )

    app_name: str = Field(
        default="Batch Downloader Scraper",
        description="FastAPI title and health service name.",
    )

    database_host: str = "localhost"

    database_port: int = 3306

    database_name: str = "batch_downloader"

    database_username: str = "batch_downloader"

    database_password: SecretStr = SecretStr("batch_downloader")

    database_pool_max: int = Field(default=8, ge=1)
    """Conexiones persistentes para workers concurrentes y consultas de API."""
    database_max_overflow: int = Field(default=4, ge=0)
    """Margen corto para que una ráfaga del pipeline no bloquee el catálogo."""
    database_pool_timeout_seconds: float = Field(default=5.0, gt=0)
    """Espera máxima para adquirir una conexión."""
    database_pool_recycle_seconds: int = Field(default=1500, ge=60)
    """Antigüedad máxima de una conexión antes de reciclarla."""
    database_url_override: str | None = Field(
        default=None,
        description="Test-only full URL override; runtime configuration uses database components.",
        exclude=True,
    )

    winstall_base_url: str = "https://winstall.app"

    winstall_api_base_url: str = "https://api.winstall.app"

    request_timeout_seconds: float = 20

    max_redirects: int = 5

    max_download_size_bytes: int = 4_000_000_000

    icon_max_bytes: int = 5_000_000

    manual_inspection_ttl_hours: int = 24

    manual_inspection_max_attempts: int = 4

    manual_page_max_bytes: int = 1_000_000

    so_filter_concurrency: int = 2

    so_filter_max_attempts: int = 4

    scrape_concurrency: int = 6

    scrape_max_apps: int = 0

    scrape_app_timeout_seconds: float = 90

    scrape_searcher_backpressure_limit: int = 250

    scrape_searcher_backpressure_sleep_seconds: float = 2

    cpu_thread_workers: int = 4

    scheduler_timezone: str = "Europe/Madrid"

    scheduler_hour: int = 3

    scheduler_minute: int = 0

    run_on_startup: bool = False

    worker_heartbeat_interval_seconds: float = Field(default=10.0, ge=1.0)
    """Cadencia de la señal persistente del scheduler."""
    worker_heartbeat_stale_seconds: float = Field(default=45.0, ge=5.0)
    """Antigüedad que degrada la capacidad del scheduler."""
    worker_failure_threshold: int = Field(default=3, ge=1)
    """Fallos consecutivos necesarios para declarar degradación."""
    url_protection_secret: str = "replace-with-a-long-random-secret"

    allowed_download_schemes: tuple[str, ...] = ("https",)

    playwright_timeout_ms: int = 15000

    internal_service_token: SecretStr = SecretStr("")

    llm_groq_api_key: str = ""

    llm_groq_base_url: str = "https://api.groq.com/openai/v1"

    llm_groq_model: str = "qwen/qwen3.6-27b"

    llm_groq_fallback_models: tuple[GroqDescriptionModel, ...] = (
        DEFAULT_GROQ_DESCRIPTION_FALLBACKS
    )

    llm_deepseek_api_key: str = ""

    llm_deepseek_base_url: str = "https://api.deepseek.com"

    llm_deepseek_model: str = "deepseek-v4-flash"

    llm_max_concurrency: int = 2

    llm_max_apps_per_run: int = 0

    llm_request_timeout_seconds: float = 45

    llm_rate_limit_cooldown_seconds: float = 3600

    llm_transient_cooldown_seconds: float = 30

    llm_model_error_cooldown_seconds: float = 86400


    @property
    def scheduler_zoneinfo(self) -> ZoneInfo:
        """Resuelve la zona IANA utilizada para interpretar la hora diaria del scheduler.

        Returns:
            zona horaria configurada.

        Raises:
            zoneinfo.ZoneInfoNotFoundError: Si no existe la zona solicitada en los datos
                horarios disponibles.
        """
        return ZoneInfo(self.scheduler_timezone)

    @property
    def database_url(self) -> URL:
        """Construye una URL SQLAlchemy sin concatenar credenciales y permite sustituirla por la
        URL explícita de pruebas.

        Returns:
            URL del driver mysql+aiomysql, o la URL de sustitución indicada.
        """
        if self.database_url_override:
            return make_url(self.database_url_override)
        return URL.create(
            drivername="mysql+aiomysql",
            username=self.database_username,
            password=self.database_password.get_secret_value(),
            host=self.database_host,
            port=self.database_port,
            database=self.database_name,
        )


@lru_cache
def get_settings() -> Settings:
    """Carga y valida la configuración en el primer acceso y reutiliza la instancia mediante
    caché del proceso.

    Returns:
        configuración compartida hasta que se invalide la caché.
    """
    return Settings()
