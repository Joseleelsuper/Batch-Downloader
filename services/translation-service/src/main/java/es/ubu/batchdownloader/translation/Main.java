package es.ubu.batchdownloader.translation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@RestController
public class Main {

    private final Path localesPath;

    public Main(@Value("${translation.locales-path:/app/locales}") String localesPath) {
        this.localesPath = Path.of(localesPath);
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @GetMapping("/translations/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "translation-service");
    }

    @GetMapping(value = "/translations/{locale}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> translation(@PathVariable String locale) throws IOException {
        Path file = localesPath.resolve(locale + ".json").normalize();
        if (!file.startsWith(localesPath.normalize()) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Files.readString(file));
    }
}
