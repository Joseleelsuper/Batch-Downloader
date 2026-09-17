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
     * Valor compartido que fija u s e r para el comportamiento del componente.
     */
    USER,
    /**
     * Valor compartido que fija a d m i n para el comportamiento del componente.
     */
    ADMIN
}
