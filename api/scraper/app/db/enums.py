"""Define estados persistidos del catálogo y del pipeline para separar disponibilidad, validación
y resultados de ejecución.
"""
from enum import StrEnum


class AppStatus(StrEnum):
    """Determina si una aplicación sigue activa en el catálogo o ha sido deshabilitada o marcada
    como rota.

    Attributes:
        ACTIVE: Aplicación activa candidata a publicación.
        DISABLED: Aplicación retirada administrativamente.
        BROKEN: Aplicación marcada con fallo persistido.
    """
    ACTIVE = "active"

    DISABLED = "disabled"

    BROKEN = "broken"



class ResolutionStatus(StrEnum):
    """Distingue el origen de una resolución del motivo por el que todavía no ofrece un
    instalador.

    Attributes:
        DIRECT: Instalador resuelto desde su origen directo.
        FALLBACK: Instalador resuelto mediante una vía alternativa.
        REQUIRES_MANUAL_REVIEW: Evidencia insuficiente que requiere revisión.
        MISSING: No se obtuvo instalador.
        BROKEN: La fuente se considera rota.
    """
    DIRECT = "direct"

    FALLBACK = "fallback"

    REQUIRES_MANUAL_REVIEW = "requires_manual_review"

    MISSING = "missing"

    BROKEN = "broken"



class ValidationStatus(StrEnum):
    """Registra qué garantía técnica conserva una fuente o resolución, independientemente de su
    origen.

    Attributes:
        UNCHECKED: Sin comprobación terminada.
        VALID: Validación aceptada.
        INVALID: La comprobación rechazó la fuente.
        EXPIRED: La comprobación dejó de ser utilizable.
    """
    UNCHECKED = "unchecked"

    VALID = "valid"

    INVALID = "invalid"

    EXPIRED = "expired"



class ScrapeRunStatus(StrEnum):
    """Distingue ejecuciones activas de las terminadas correctamente, parcialmente o con fallo."""
    RUNNING = "running"

    COMPLETED = "completed"

    PARTIAL = "partial"

    FAILED = "failed"



class ScrapeScope(StrEnum):
    """Fija el conjunto de aplicaciones que una solicitud debe procesar sin cambiarlo durante su
    ejecución.

    Attributes:
        INCREMENTAL: Busca cambios del catálogo proveedor.
        UNRESOLVED: Selecciona aplicaciones sin resolución suficiente.
        SELECTED: Procesa los UUID explícitos de la solicitud.
        FULL: Recorre el conjunto completo solicitado al proveedor.
    """

    INCREMENTAL = "incremental"
    UNRESOLVED = "unresolved"
    SELECTED = "selected"
    FULL = "full"


class ScrapeOutcome(StrEnum):
    """Clasifica el resultado por aplicación y distingue resolución, ausencia confirmada,
    revisión, fallo transitorio y omisión por falta de cambios.
    """

    RESOLVED = "resolved"
    CONFIRMED_MISSING = "confirmed_missing"
    NEEDS_REVIEW = "needs_review"
    TRANSIENT_FAILED = "transient_failed"
    SKIPPED_UNCHANGED = "skipped_unchanged"


class AbsenceVerificationStatus(StrEnum):
    """Controla la vigencia del acta que acredita una ausencia comprobada de instaladores.

    Attributes:
        ACTIVE: Acta actualmente aplicable a la evidencia capturada.
        INVALIDATED: Acta retirada tras cambiar datos relevantes o revisarse su conclusión.
        SUPERSEDED: Acta sustituida por otra comprobación.
    """

    ACTIVE = "active"
    INVALIDATED = "invalidated"
    SUPERSEDED = "superseded"


class LongDescriptionStatus(StrEnum):
    """Distingue descripciones pendientes, completadas, fallidas y omitidas por el proceso de
    enriquecimiento.
    """
    PENDING = "pending"

    COMPLETED = "completed"

    FAILED = "failed"

    SKIPPED = "skipped"

