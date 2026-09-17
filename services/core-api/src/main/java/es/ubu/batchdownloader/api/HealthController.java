package es.ubu.batchdownloader.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone una respuesta ligera que identifica al proceso Core cuando su servidor HTTP puede atender
 * peticiones; no comprueba dependencias externas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ApiErrorController
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
@RestController
public class HealthController {
    /**
     * Responde con status ok y service core-api sin realizar consultas de red ni de base de datos.
     *
     * @return identidad del servicio y disponibilidad de esta ruta HTTP.
     */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "core-api");
    }
}
