package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.PasswordHasher;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gestiona registro, perfil y tokens de un solo uso, manteniendo atómicos cuenta, consumo y eventos
 * de correo y evitando bloqueos durante BCrypt.
 *
 * @see es.ubu.batchdownloader.identity.application.port.UserAccountStore
 * @see es.ubu.batchdownloader.identity.application.port.IdentityTokenStore
 * @see es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher
 * @see es.ubu.batchdownloader.identity.application.PasswordPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Service
public class IdentityService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] USERNAME_SUFFIX = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final UserAccountStore users;
    private final IdentityTokenStore tokens;
    private final PasswordHasher passwords;
    private final IdentityEventPublisher events;
    private final AccountSessionInvalidator sessions;
    private final Clock clock;
    private final Duration verificationTtl;
    private final Duration resetTtl;
    private final TransactionTemplate transactions;

    /**
     * Conecta los puertos de cuenta, tokens, hash, correo y sesiones con el reloj y las vigencias
     * de identidad.
     *
     * @param users Persistencia de cuentas y consultas de unicidad de correo y nombre normalizados.
     * @param tokens Persistencia de tokens con bloqueo de consumo e invalidación de los anteriores.
     * @param passwords Puerto de hash de contraseñas; recibe entradas previamente validadas para
     *     BCrypt.
     * @param events Publicación durable de solicitudes de verificación y recuperación de
     *     contraseña.
     * @param sessions Puerto que invalida las sesiones asociadas al UUID de la cuenta.
     * @param clock Reloj usado para creación, caducidad y consumo de tokens y cambios de cuenta.
     * @param verificationTtl Vigencia de los nuevos tokens de verificación de correo.
     * @param resetTtl Vigencia de los nuevos tokens para restablecer contraseña.
     * @param transactions Ejecutor que acota reservas de nombre y consumo de tokens sin mantener
     *     bloqueos durante BCrypt.
     */
    public IdentityService(
            UserAccountStore users,
            IdentityTokenStore tokens,
            PasswordHasher passwords,
            IdentityEventPublisher events,
            AccountSessionInvalidator sessions,
            Clock clock,
            @Value("${app.auth.verification-ttl}") Duration verificationTtl,
            @Value("${app.auth.password-reset-ttl}") Duration resetTtl,
            TransactionTemplate transactions) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.events = events;
        this.sessions = sessions;
        this.clock = clock;
        this.verificationTtl = verificationTtl;
        this.resetTtl = resetTtl;
        this.transactions = transactions;
    }

    /**
     * Valida y calcula el hash una sola vez; intenta reservar hasta doce nombres y guarda cuenta,
     * token de verificación y correo en una misma transacción.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @return cuenta nueva habilitada y pendiente de verificar correo.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la contraseña incumple la
     *     política.
     * @throws es.ubu.batchdownloader.common.ConflictException si el correo existe o no se logra
     *     reservar un nombre único.
     */
    public IdentityView register(String email, String rawPassword) {
        PasswordPolicy.requireValid(rawPassword);
        String cleanEmail = cleanEmail(email);
        String normalizedEmail = normalize(cleanEmail);
        if (users.existsByNormalizedEmail(normalizedEmail)) throw emailAlreadyExists();

        String passwordHash = passwords.hash(rawPassword);
        String baseUsername = UsernamePolicy.fromEmail(cleanEmail);
        for (int attempt = 0; attempt < 12; attempt++) {
            String username = attempt == 0
                    ? baseUsername
                    : UsernamePolicy.collisionCandidate(baseUsername, randomUsernameSuffix());
            try {
                IdentityView created = transactions.execute(status -> {
                    if (users.existsByNormalizedEmail(normalizedEmail)) throw emailAlreadyExists();
                    if (users.existsByNormalizedUsername(UsernamePolicy.normalize(username))) {
                        throw new UsernameCollisionException();
                    }
                    Instant now = clock.instant();
                    UserAccount user = users.save(UserAccount.register(
                            username,
                            UsernamePolicy.normalize(username),
                            cleanEmail,
                            normalizedEmail,
                            passwordHash,
                            now));
                    issueToken(user, IdentityToken.Type.EMAIL_VERIFICATION, verificationTtl);
                    return view(user);
                });
                if (created != null) return created;
            } catch (UsernameCollisionException exception) {
                // Se elige un nuevo sufijo sin repetir BCrypt.
            } catch (DataIntegrityViolationException exception) {
                if (users.existsByNormalizedEmail(normalizedEmail)) throw emailAlreadyExists();
            }
        }
        throw new ConflictException(
                "username_generation_failed", "No se pudo reservar un username para la cuenta.");
    }

    /**
     * Proyecta una cuenta habilitada a sus datos de sesión y preferencias.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @return vista sin hash de contraseña.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el UUID no existe o está
     *     deshabilitado.
     */
    @Transactional(readOnly = true)
    public IdentityView findById(UUID id) {
        return view(requireById(id));
    }

    /**
     * Normaliza el nombre y proyecta la cuenta habilitada coincidente.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @return vista de identidad sin credenciales.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no hay una cuenta habilitada con
     *     ese nombre.
     */
    @Transactional(readOnly = true)
    public IdentityView findByUsername(String username) {
        return view(requireByUsername(username));
    }

    /**
     * Exige que el UUID corresponda a una cuenta habilitada.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @return agregado de la cuenta.
     * @throws es.ubu.batchdownloader.common.NotFoundException si falta o está deshabilitada.
     */
    @Transactional(readOnly = true)
    public UserAccount requireById(UUID id) {
        return users.findById(id).filter(UserAccount::enabled)
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
    }

    /**
     * Busca el nombre normalizado y exige una cuenta habilitada.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @return agregado coincidente.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe o está deshabilitado.
     */
    @Transactional(readOnly = true)
    public UserAccount requireByUsername(String username) {
        return users.findByNormalizedUsername(normalize(username)).filter(UserAccount::enabled)
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
    }

    /**
     * Bloquea un token de verificación utilizable y confirma correo y consumo en la misma
     * transacción.
     *
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el token o su cuenta no existen.
     * @throws es.ubu.batchdownloader.common.GoneException si el token ya se consumió o venció.
     */
    @Transactional
    public void confirmEmail(String rawToken) {
        IdentityToken token = requireUsableToken(rawToken, IdentityToken.Type.EMAIL_VERIFICATION, true);
        UserAccount user = users.findById(token.userId())
                .orElseThrow(() -> invalidToken(IdentityToken.Type.EMAIL_VERIFICATION));
        Instant now = clock.instant();
        user.verifyEmail(now);
        token.consume(now);
        users.save(user);
        tokens.save(token);
    }

    /**
     * Emite otro token solo para cuentas habilitadas sin verificar; cuentas ausentes o ya
     * verificadas no revelan una respuesta distinta.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     */
    @Transactional
    public void resendEmailVerification(String email) {
        users.findByNormalizedEmail(normalize(email))
                .filter(UserAccount::enabled)
                .filter(user -> !user.emailVerified())
                .ifPresent(user -> issueToken(user, IdentityToken.Type.EMAIL_VERIFICATION, verificationTtl));
    }

    /**
     * Emite un token de recuperación únicamente para una cuenta habilitada; la ausencia de cuenta
     * conserva una respuesta uniforme.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByNormalizedEmail(normalize(email))
                .filter(UserAccount::enabled)
                .ifPresent(user -> issueToken(user, IdentityToken.Type.PASSWORD_RESET, resetTtl));
    }

    /**
     * Comprueba el token, calcula BCrypt fuera del bloqueo y vuelve a validarlo bajo reserva antes
     * de guardar contraseña y consumo; después invalida las sesiones de la cuenta.
     *
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     * @param newPassword Nueva contraseña que debe cumplir la política vigente antes de calcular
     *     BCrypt.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la contraseña o el token son
     *     inválidos.
     * @throws es.ubu.batchdownloader.common.GoneException si el token caducó o fue consumido,
     *     incluido entre ambas comprobaciones.
     */
    public void resetPassword(String rawToken, String newPassword) {
        PasswordPolicy.requireValid(newPassword);
        transactions.executeWithoutResult(status ->
                requireUsableToken(rawToken, IdentityToken.Type.PASSWORD_RESET, false));
        String passwordHash = passwords.hash(newPassword);
        UserAccount changed = transactions.execute(status -> {
            IdentityToken token = requireUsableToken(rawToken, IdentityToken.Type.PASSWORD_RESET, true);
            UserAccount user = users.findById(token.userId())
                    .orElseThrow(() -> invalidToken(IdentityToken.Type.PASSWORD_RESET));
            Instant now = clock.instant();
            user.changePassword(passwordHash, now);
            token.consume(now);
            tokens.save(token);
            return users.save(user);
        });
        if (changed != null) sessions.invalidateAll(changed.id());
    }

    /**
     * Valida el nombre manual y su unicidad normalizada; convierte también una colisión concurrente
     * de persistencia en conflicto de nombre.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param requestedUsername Nombre visible propuesto por el usuario, validado y normalizado
     *     antes de guardarlo.
     * @return vista actualizada con el mismo UUID.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el formato es inválido.
     * @throws es.ubu.batchdownloader.common.ConflictException si otra cuenta ocupa el nombre.
     */
    @Transactional
    public IdentityView updateUsername(UUID userId, String requestedUsername) {
        UserAccount user = requireById(userId);
        String clean = UsernamePolicy.validateManual(requestedUsername);
        String normalized = UsernamePolicy.normalize(clean);
        if (!normalized.equals(user.normalizedUsername()) && users.existsByNormalizedUsername(normalized)) {
            throw usernameTaken();
        }
        try {
            user.changeUsername(clean, normalized, clock.instant());
            return view(users.save(user));
        } catch (DataIntegrityViolationException exception) {
            throw usernameTaken();
        }
    }

    /**
     * Guarda la preferencia de avisos y su fecha de cambio en la cuenta habilitada.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param enabled Activa o desactiva el correo al terminar descargas.
     * @return vista actualizada de identidad y preferencias.
     */
    @Transactional
    public IdentityView updateNotificationPreference(UUID userId, boolean enabled) {
        UserAccount user = requireById(userId);
        user.updateNotificationPreference(enabled, clock.instant());
        return view(users.save(user));
    }

    /**
     * Confirma el correo solo si la cuenta todavía no estaba verificada.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @return cuenta habilitada, guardada únicamente cuando cambia su verificación.
     */
    @Transactional
    public UserAccount markVerified(UUID userId) {
        UserAccount user = requireById(userId);
        if (!user.emailVerified()) {
            user.verifyEmail(clock.instant());
            return users.save(user);
        }
        return user;
    }

    /**
     * Selecciona los datos de cuenta que pueden exponerse sin incluir credenciales ni hashes de
     * tokens.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @return vista pública de la identidad solicitada.
     */
    public IdentityView view(UserAccount user) {
        return IdentityView.from(user);
    }

    /**
     * Invalida tokens pendientes de la misma finalidad, persiste el hash de uno nuevo y solicita su
     * correo dentro de la transacción del llamador.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param ttl Duración de validez del token nuevo desde el reloj del caso de uso.
     */
    private void issueToken(UserAccount user, IdentityToken.Type type, Duration ttl) {
        Instant now = clock.instant();
        tokens.invalidateUnconsumedForUser(user.id(), type, now);
        String rawToken = newRawToken();
        tokens.save(IdentityToken.issue(user.id(), hashToken(rawToken), type, now.plus(ttl), now));
        if (type == IdentityToken.Type.EMAIL_VERIFICATION) {
            events.emailVerificationRequested(user, rawToken);
        } else {
            events.passwordResetRequested(user, rawToken);
        }
    }

    /**
     * Consulta por hash y finalidad, opcionalmente con bloqueo, y distingue ausencia, consumo
     * previo y caducidad.
     *
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param forUpdate Solicita bloqueo de escritura al consultar el token antes de consumirlo.
     * @return token todavía utilizable; no lo consume.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el hash no corresponde a un
     *     token de esa finalidad.
     * @throws es.ubu.batchdownloader.common.GoneException si ya está consumido o no vence después
     *     del instante actual.
     */
    private IdentityToken requireUsableToken(
            String rawToken, IdentityToken.Type type, boolean forUpdate) {
        String hash = hashToken(rawToken);
        IdentityToken token = (forUpdate
                        ? tokens.findByHashAndTypeForUpdate(hash, type)
                        : tokens.findByHashAndType(hash, type))
                .orElseThrow(() -> invalidToken(type));
        if (token.consumedAt() != null) throw usedToken(type);
        if (!token.expiresAt().isAfter(clock.instant())) throw expiredToken(type);
        return token;
    }

    /**
     * Clasifica un token inexistente con el prefijo propio de verificación o recuperación.
     *
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return error de entrada con código token_invalid de la finalidad correspondiente.
     */
    private BadRequestException invalidToken(IdentityToken.Type type) {
        return new BadRequestException(tokenPrefix(type) + "_token_invalid", "El token no es válido.");
    }

    /**
     * Clasifica un token cuya fecha de validez ya terminó.
     *
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return error Gone con código token_expired de la finalidad.
     */
    private GoneException expiredToken(IdentityToken.Type type) {
        return new GoneException(tokenPrefix(type) + "_token_expired", "El token ha caducado.");
    }

    /**
     * Clasifica un token consumido o invalidado previamente.
     *
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return error Gone con código token_used de la finalidad.
     */
    private GoneException usedToken(IdentityToken.Type type) {
        return new GoneException(tokenPrefix(type) + "_token_used", "El token ya se ha utilizado.");
    }

    /**
     * Distingue los códigos de error de verificación y recuperación sin exponer el token.
     *
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @return verification o reset.
     */
    private String tokenPrefix(IdentityToken.Type type) {
        return type == IdentityToken.Type.EMAIL_VERIFICATION ? "verification" : "reset";
    }

    /**
     * Representa una colisión del correo normalizado durante el registro.
     *
     * @return conflicto email_already_exists.
     */
    private ConflictException emailAlreadyExists() {
        return new ConflictException("email_already_exists", "El correo ya está registrado.");
    }

    /**
     * Representa una colisión del nombre normalizado durante la edición del perfil.
     *
     * @return conflicto username_taken.
     */
    private ConflictException usernameTaken() {
        return new ConflictException("username_taken", "El username ya está ocupado.");
    }

    /**
     * Retira espacios extremos manteniendo las mayúsculas del correo visible.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @return correo recortado o cadena vacía para null.
     */
    private static String cleanEmail(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Obtiene la clave de comparación recortando y convirtiendo a minúsculas con Locale.ROOT.
     *
     * @param value Texto que se normaliza o valida según el contrato del método.
     * @return texto normalizado o cadena vacía si la entrada es null.
     */
    public static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Calcula SHA-256 sobre los bytes UTF-8 del token para almacenarlo y consultarlo sin su valor
     * original.
     *
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     * @return 64 caracteres hexadecimales en minúsculas.
     * @throws IllegalStateException si el entorno no proporciona SHA-256.
     */
    public static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Genera 32 bytes criptográficamente aleatorios y los codifica como Base64 URL sin relleno.
     *
     * @return token opaco que debe entregarse al usuario y almacenarse solo como hash.
     */
    private static String newRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Elige ocho caracteres aleatorios del alfabeto configurado para resolver colisiones de
     * nombres.
     *
     * @return sufijo que se añade al nombre base recortado.
     */
    private static String randomUsernameSuffix() {
        char[] suffix = new char[8];
        for (int index = 0; index < suffix.length; index++) {
            suffix[index] = USERNAME_SUFFIX[SECURE_RANDOM.nextInt(USERNAME_SUFFIX.length)];
        }
        return new String(suffix);
    }

    /**
     * Interrumpe una reserva transaccional de nombre ocupado para que el registro reintente con
     * otro sufijo sin repetir BCrypt.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    private static final class UsernameCollisionException extends RuntimeException {}
}
