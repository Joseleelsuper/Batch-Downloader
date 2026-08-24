package es.ubu.batchdownloader.catalog;

/** Utilidades SQL compartidas por las lecturas del catálogo. */
final class CatalogSql {
    private CatalogSql() {}

    /** Añade marcadores JDBC separados por comas para una colección ya validada. */
    static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
    }
}
