"""Carga configuración SEMANTIC_ y calcula límites de pool y ventanas de trabajo de los tres
procesos del servicio.
"""
from __future__ import annotations

from datetime import UTC, datetime
from functools import lru_cache
from zoneinfo import ZoneInfo

from psycopg.conninfo import make_conninfo
from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Valida la configuración compartida por API, indexador y trabajador de modelos desde
    entorno y archivo .env.
    Las contraseñas y el token usan SecretStr; postgres_dsn contiene credenciales y no debe
    registrarse.

    Attributes:
        database_host, database_port, database_name, database_username, database_password:
            Parámetros de conexión PostgreSQL.
        database_role: Selecciona los límites del pool: api, indexer o model_worker.
        api_db_pool_min, api_db_pool_max: Conexiones mínima y máxima para peticiones HTTP.
        indexer_db_pool_min, indexer_db_pool_max: Conexiones mínima y máxima del indexador.
        model_worker_db_pool_min, model_worker_db_pool_max: Conexiones mínima y máxima del
            trabajador administrativo.
        db_pool_timeout_seconds, db_pool_max_lifetime_seconds: Espera de reserva y vida máxima
            de conexiones, en segundos.
        postgres_dsn_override: DSN opcional para pruebas, excluido de la serialización.
        internal_service_token: Credencial para llamadas internas entre servicios.
        scraper_api_url, service_url: Bases HTTP de Scraper y del propio API semántico.
        model_cache_dir, reports_dir: Directorios locales de artefactos y reportes.
        device, initial_model_version: Dispositivo de inferencia y versión inicial para el
            indexador.
        candidate_limit, minimum_similarity: Máximo funcional de candidatos y umbral de
            similitud por defecto.
        index_batch_size: Número máximo de documentos por lote de codificación.
        index_interval_seconds, index_lease_seconds: Intervalo de barrido y duración de
            reserva de embeddings.
        search_timeout_seconds: Tiempo máximo de codificación de una consulta en la ruta HTTP.
        search_concurrency, search_capacity_wait_seconds: Plazas de búsqueda y tiempo máximo
            de admisión.
        background_timezone, background_start_hour, background_end_hour: Zona y horas de la
            ventana de fondo; inicio igual a fin permite todo el día.
        operation_poll_seconds, operation_lease_seconds: Espera de cola y duración de reserva
            administrativa.
        retention_interval_seconds: Intervalo entre limpiezas de historial.
        worker_heartbeat_interval_seconds, worker_heartbeat_stale_seconds: Periodo y
            antigüedad máxima de latidos.
        worker_failure_threshold: Racha de fallos que degrada la salud de un trabajador.
        model_max_bytes, model_min_free_bytes: Presupuesto de artefactos y reserva mínima de
            espacio libre en bytes.
        trainer_seed, trainer_epochs, trainer_batch_size, trainer_max_steps: Semilla, épocas,
            tamaño de lote y límite de pasos del entrenamiento.
        trainer_models: Claves de modelos base que participan en la campaña de comparación.
    """
    model_config = SettingsConfigDict(
        env_prefix="SEMANTIC_",
        env_file=".env",
        extra="ignore",
    )


    database_host: str = "localhost"

    database_port: int = 5432

    database_name: str = "batch_downloader_semantic"

    database_username: str = "batch_downloader"

    database_password: SecretStr = SecretStr("batch_downloader")

    database_role: str = Field(default="api", pattern="^(api|indexer|model_worker)$")
    """Rol del proceso que determina el presupuesto del pool."""
    api_db_pool_min: int = Field(default=1, ge=0)
    api_db_pool_max: int = Field(default=3, ge=1)
    indexer_db_pool_min: int = Field(default=0, ge=0)
    indexer_db_pool_max: int = Field(default=1, ge=1)
    model_worker_db_pool_min: int = Field(default=0, ge=0)
    model_worker_db_pool_max: int = Field(default=1, ge=1)
    db_pool_timeout_seconds: float = Field(default=2.0, gt=0)
    """Espera máxima para adquirir PostgreSQL."""
    db_pool_max_lifetime_seconds: float = Field(default=1500.0, ge=60)
    """Vida máxima de una conexión del pool."""
    postgres_dsn_override: str | None = Field(default=None, exclude=True)

    internal_service_token: SecretStr = SecretStr("")

    scraper_api_url: str = "http://scraper-api:8000"

    service_url: str = "http://semantic-service:8000"

    model_cache_dir: str = "/models"

    reports_dir: str = "/reports"

    device: str = "cpu"

    initial_model_version: str = (
        "multilingual-e5-base@d128750597153bb5987e10b1c3493a34e5a4502a:zero-shot"
    )

    candidate_limit: int = 20000

    minimum_similarity: float = 0.82

    index_batch_size: int = 32

    index_interval_seconds: float = 300.0

    index_lease_seconds: int = 900

    search_timeout_seconds: float = 3.0

    search_concurrency: int = Field(default=2, ge=1)
    """Búsquedas semánticas que pueden usar CPU y PostgreSQL simultáneamente."""
    search_capacity_wait_seconds: float = Field(default=2.0, gt=0)
    """Espera máxima para obtener una plaza de búsqueda."""
    background_timezone: str = "Europe/Madrid"
    """Zona horaria utilizada para aislar los trabajos pesados."""
    background_start_hour: int = Field(default=1, ge=0, le=23)
    """Primera hora incluida de la ventana fuera de punta."""
    background_end_hour: int = Field(default=7, ge=0, le=23)
    """Última hora excluida de la ventana fuera de punta."""
    operation_poll_seconds: float = 2.0

    operation_lease_seconds: int = 300

    retention_interval_seconds: float = Field(default=21_600.0, ge=60.0)
    """Intervalo entre pasadas acotadas de retención del model worker."""
    worker_heartbeat_interval_seconds: float = Field(default=10.0, ge=1.0)
    """Cadencia de la señal persistente emitida por cada worker."""
    worker_heartbeat_stale_seconds: float = Field(default=45.0, ge=5.0)
    """Antigüedad a partir de la cual un worker se considera degradado."""
    worker_failure_threshold: int = Field(default=3, ge=1)
    """Fallos consecutivos necesarios para degradar un worker activo."""
    model_max_bytes: int = 16_106_127_360

    model_min_free_bytes: int = 10_737_418_240

    trainer_seed: int = 20260723

    trainer_epochs: float = 1.0

    trainer_batch_size: int = 8

    trainer_max_steps: int = -1

    trainer_models: tuple[str, ...] = (
        "paraphrase-multilingual-MiniLM-L12-v2",
        "multilingual-e5-base",
        "bge-m3",
    )


    @property
    def postgres_dsn(self) -> str:
        """Devuelve el DSN explícito de pruebas o compone uno escapado mediante psycopg a partir
        de sus campos.

        Returns:
            cadena de conexión con contraseña; no debe aparecer en logs.
        """
        if self.postgres_dsn_override:
            return self.postgres_dsn_override
        return make_conninfo(
            host=self.database_host,
            port=self.database_port,
            dbname=self.database_name,
            user=self.database_username,
            password=self.database_password.get_secret_value(),
        )

    @property
    def database_pool_limits(self) -> tuple[int, int]:
        """Selecciona el par de límites correspondiente al rol configurado del proceso.

        Returns:
            mínimo y máximo de conexiones para API, indexador o trabajador de modelos.
        """
        if self.database_role == "indexer":
            return self.indexer_db_pool_min, self.indexer_db_pool_max
        if self.database_role == "model_worker":
            return self.model_worker_db_pool_min, self.model_worker_db_pool_max
        return self.api_db_pool_min, self.api_db_pool_max

    def background_window_open(self, moment: datetime | None = None) -> bool:
        """Comprueba la hora local de una ventana con inicio inclusivo y fin exclusivo, incluida
        la que cruza medianoche.

        Args:
            moment: Instante opcional que se convierte a la zona de la ventana; None usa ahora
                en UTC.

        Returns:
            True dentro de la ventana; si inicio y fin coinciden permite las 24 horas.
        """
        current = (moment or datetime.now(UTC)).astimezone(
            ZoneInfo(self.background_timezone)
        )
        start = self.background_start_hour
        end = self.background_end_hour
        if start == end:
            return True
        if start < end:
            return start <= current.hour < end
        return current.hour >= start or current.hour < end


@lru_cache
def get_settings() -> Settings:
    """Carga y valida la configuración una vez y reutiliza la misma instancia durante el proceso.

    Returns:
        configuración memorizada; las pruebas pueden vaciar la caché para cambiar el entorno.
    """
    return Settings()
