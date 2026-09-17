package es.ubu.batchdownloader.common;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Convierte UUID entre la representación Java y los 16 bytes que usa MySQL sin intercambiar el
 * orden de sus componentes.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class UuidBytes {
    /**
     * Impide instancias del conversor estático de UUID binarios.
     */
    private UuidBytes() {}

    /**
     * Escribe primero los 64 bits más significativos y después los menos significativos en big
     * endian.
     *
     * @param uuid UUID que se representa en dieciséis bytes, con los bits más significativos
     *     primero.
     * @return nuevo array de 16 bytes.
     */
    public static byte[] fromUuid(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    /**
     * Lee en big endian los dos componentes de 64 bits del UUID binario.
     *
     * @param bytes Representación binaria del UUID; se leen los primeros dieciséis bytes en orden
     *     big endian.
     * @return UUID reconstruido.
     * @throws java.nio.BufferUnderflowException si la entrada tiene menos de dieciséis bytes.
     */
    public static UUID toUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
