package es.ubu.batchdownloader.catalog;

/**
 * Añade marcadores de SQL parametrizado compartidos por las consultas del catálogo.
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
final class CatalogSql {
    /**
     * Impide instancias del constructor estático de marcadores SQL.
     */
    private CatalogSql() {}

    /**
     * Añade la cantidad indicada de interrogantes separados por comas sin interpolar valores.
     *
     * @param sql Sentencia en construcción, formada solo por fragmentos constantes y marcadores de
     *     parámetros.
     * @param count Número de parámetros que se enlazarán después; cero no añade texto.
     */
    static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
    }
}
