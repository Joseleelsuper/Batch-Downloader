package es.ubu.batchdownloader.identity.application.port;

import java.time.Duration;

/** Cuotas compartidas por huella opaca; no recibe direcciones, cuentas ni credenciales. */
public interface AuthAttemptBudget {
    boolean allow(String scope, String fingerprint, int limit, Duration window);
}
