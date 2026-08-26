-- Base mínima para instalaciones nuevas. V3 conserva su FK histórica mientras
-- el catálogo público pasa a proyecciones propiedad del Core en V4.
CREATE TABLE IF NOT EXISTS software_apps (
    id BINARY(16) NOT NULL,
    PRIMARY KEY (id)
);
