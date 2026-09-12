package es.ubu.batchdownloader.identity.domain;

/**
 * Distingue cuentas de usuario y administración para asignar sus autoridades de acceso.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public enum UserRole {
    /**
     * Constante que define {@code USER}.
     */
    USER,
    /**
     * Constante que define {@code ADMIN}.
     */
    ADMIN
}
