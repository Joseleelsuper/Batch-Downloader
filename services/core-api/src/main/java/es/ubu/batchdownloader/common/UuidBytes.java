package es.ubu.batchdownloader.common;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Implementa el componente {@code UuidBytes}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class UuidBytes {
    /**
     * Inicializa una instancia de {@code UuidBytes}.
     */
    private UuidBytes() {}

    /**
     * Ejecuta la operación {@code fromUuid}.
     *
     * @param uuid Valor de {@code uuid} utilizado por la operación.
     * @return Resultado producido por {@code fromUuid}.
     */
    public static byte[] fromUuid(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /**
     * Convierte el valor recibido mediante {@code toUuid}.
     *
     * @param bytes Valor de {@code bytes} utilizado por la operación.
     * @return Resultado producido por {@code toUuid}.
     */
    public static UUID toUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
