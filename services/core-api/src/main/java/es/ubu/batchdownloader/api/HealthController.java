package es.ubu.batchdownloader.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone las operaciones HTTP gestionadas por {@code HealthController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
public class HealthController {
    /**
     * Ejecuta la operación {@code health}.
     *
     * @return Mapa con los datos producidos por la operación.
     */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "core-api");
    }
}
