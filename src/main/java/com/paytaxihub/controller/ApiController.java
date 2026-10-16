package com.paytaxihub.controller;

import com.paytaxihub.entity.*;
import com.paytaxihub.auth.AuthSessionService;
import com.paytaxihub.auth.PasswordResetService;
import com.paytaxihub.repository.*;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseCookie;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final UserRepository users;
    private final DriverRepository drivers;
    private final FleetOwnerRepository owners;
    private final VehicleRepository vehicles;
    private final WalletRepository wallets;
    private final RouteRepository routes;
    private final TripRepository trips;
    private final TransactionRepository transactions;
    private final SOSEventRepository sos;
    private final ShiftRepository shifts;
    private final PasswordEncoder encoder;
    private final AuthSessionService sessions;
    private final PasswordResetService passwordResets;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;
    @Value("${app.session.cookie-secure:true}") private boolean secureSessionCookie;
    @Value("${app.password-reset.expose-code:false}") private boolean exposePasswordResetCode;
    @Value("${app.mfa.code:}") private String mfaCode;
    @Value("${app.admin.bootstrap-key:}") private String adminBootstrapKey = "";
    @Value("${app.admin.bootstrap-email:}") private String adminBootstrapEmail = "";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiController.class);
    private static final String DUPLICATE_MESSAGE = "We could not complete registration with those details. Check the information and try again.";
    private static final String BAD_CREDENTIALS = "Sign-in failed. Check your details and try again.";
    private static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
    /** Real hash generated at startup; compared against when the email is unknown so response time does not reveal which emails exist. */
    private final String dummyHash;
    private static final int MAX_FAILED_LOGINS = 5;
    private static final long LOCK_WINDOW_MS = 15L * 60L * 1000L;
    private final Map<String, long[]> failedLogins = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isLockedOut(String key) {
        long[] e = failedLogins.get(key);
        if (e == null) return false;
        if (System.currentTimeMillis() - e[1] > LOCK_WINDOW_MS) { failedLogins.remove(key); return false; }
        return e[0] >= MAX_FAILED_LOGINS;
    }

    private void recordFailedLogin(String key) {
        long now = System.currentTimeMillis();
        failedLogins.compute(key, (k, e) -> (e == null || now - e[1] > LOCK_WINDOW_MS) ? new long[]{1, now} : new long[]{e[0] + 1, now});
        if (failedLogins.size() > 5000) failedLogins.entrySet().removeIf(en -> now - en.getValue()[1] > LOCK_WINDOW_MS);
    }

    public ApiController(UserRepository users, DriverRepository drivers, FleetOwnerRepository owners, VehicleRepository vehicles, WalletRepository wallets, RouteRepository routes, TripRepository trips, TransactionRepository transactions, SOSEventRepository sos, ShiftRepository shifts, PasswordEncoder encoder, AuthSessionService sessions, PasswordResetService passwordResets, org.springframework.transaction.PlatformTransactionManager txManager) {
        this.users = users;
        this.drivers = drivers;
        this.owners = owners;
        this.vehicles = vehicles;
        this.wallets = wallets;
        this.routes = routes;
        this.trips = trips;
        this.transactions = transactions;
        this.sos = sos;
        this.shifts = shifts;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());
        this.sessions = sessions;
        this.passwordResets = passwordResets;
        this.txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    private Map<String, Object> userDto(User u) {
        return map("userId", u.getUserId(), "fullName", u.getFullName(), "email", u.getEmail(), "phoneNumber", u.getPhoneNumber(), "role", u.getRole().name(), "createdAt", u.getCreatedAt(), "isActive", !Boolean.FALSE.equals(u.getIsActive()));
    }

    private Integer authUserId(Integer id) { return id; }

    /** These handlers catch exceptions themselves, so without this the surrounding transaction would still COMMIT half-finished work. */
    private void markRollback() {
        try { org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
        catch (org.springframework.transaction.NoTransactionException ignored) { }
    }

    private Integer requireUserId(Integer authUserId) {
        if (authUserId == null) throw new SecurityException("Authentication required.");
        return authUserId;
    }

    /** Administrator is a platform-wide role; resource ownership checks still apply to non-admin users. */
    private void requireRole(String actualRole, User.Role... allowed) {
        if (actualRole == null) throw new SecurityException("Authentication required.");
        if (User.Role.Admin.name().equals(actualRole)) return;
        for (User.Role r : allowed) if (r.name().equals(actualRole)) return;
        throw new SecurityException("You do not have permission to perform this action.");
    }

    private Driver requireDriverForUser(Integer driverId, Integer userId) {
        Driver d = drivers.findById(driverId).orElseThrow();
        if (!Objects.equals(d.getUser().getUserId(), userId)) throw new SecurityException("You can only access your own driver account.");
        return d;
    }

    private Driver requireDriverForOwner(Integer driverId, Integer userId) {
        Driver d=drivers.findById(driverId).orElseThrow();
        FleetOwner owner=owners.findByUser_UserId(userId).orElseThrow(()->new SecurityException("Fleet owner profile not found."));
        if(d.getFleetOwner()==null || !Objects.equals(d.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())) throw new SecurityException("You can only access drivers assigned to your fleet.");
        return d;
    }

    /** Only our own validation messages (IllegalArgumentException) reach the user; anything else is logged and replaced. */
    private String safeMessage(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null && !e.getMessage().isBlank()) return e.getMessage();
        LOG.error("Registration failed", e);
        return "Registration could not be completed. Please try again.";
    }

    /** Never reveal which detail (email, phone, ID) already exists; that would disclose other people's data. */
    private String duplicateConstraintMessage(DataIntegrityViolationException e) {
        return DUPLICATE_MESSAGE;
    }

    /** Turns any exception into a short, safe message. Internal text (SQL, class names, "No value present") is logged, never shown. */
    private ResponseEntity<?> errorResponse(Exception e) {
        if (e instanceof SecurityException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(map("message", e.getMessage() == null ? "You do not have permission to perform this action." : e.getMessage()));
        }
        if (e instanceof NoSuchElementException) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(map("message", "The requested record was not found."));
        }
        if (e instanceof DataIntegrityViolationException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(map("message", "That change conflicts with existing data. Please check your details and try again."));
        }
        if (e instanceof IllegalArgumentException) {
            String m = e.getMessage();
            boolean technical = m == null || m.isBlank() || m.startsWith("No enum constant") || m.contains("For input string") || e instanceof NumberFormatException;
            return ResponseEntity.badRequest().body(map("message", technical ? "One of the values you entered is not valid. Please check and try again." : m));
        }
        LOG.error("Request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(map("message", "Something went wrong on our side. Please try again."));
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return map("status", "UP", "service", "PayTaxiHub", "time", LocalDateTime.now());
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Admin);
        return users.findAll().stream().map(this::userDto).toList();
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable Integer id, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (!Objects.equals(id, authUserId)) requireRole(authRole, User.Role.Admin);
        return userDto(users.findById(id).orElseThrow());
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> p, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Admin);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(txTemplate.execute(status -> register(p, false)));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(map("message", duplicateConstraintMessage(e)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(map("message", safeMessage(e)));
        }
    }

    @PostMapping("/internal/bootstrap-admin")
    public ResponseEntity<?> bootstrapAdmin(
            @RequestHeader(value = "X-Admin-Bootstrap-Key", required = false) String suppliedKey,
            @RequestBody Map<String, Object> payload) {
        if (adminBootstrapKey.isBlank() || adminBootstrapEmail.isBlank() || suppliedKey == null
                || !java.security.MessageDigest.isEqual(
                        adminBootstrapKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        suppliedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> admin = new HashMap<>(payload);
        admin.put("email", adminBootstrapEmail);
        admin.put("role", User.Role.Admin.name());
        try {
            Map<String, Object> user = txTemplate.execute(status -> register(admin, true));
            return ResponseEntity.status(HttpStatus.CREATED).body(map("user", user));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(map("message", duplicateConstraintMessage(e)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(map("message", safeMessage(e)));
        }
    }

    // Called through txTemplate (a @Transactional on a private method is ignored by Spring), so user+wallet+profile are saved atomically.
    private Map<String, Object> register(Map<String, Object> p, boolean allowAdmin) {
        String full = String.valueOf(p.getOrDefault("fullName", "")).trim(), email = String.valueOf(p.getOrDefault("email", "")).trim().toLowerCase(), phone = String.valueOf(p.getOrDefault("phoneNumber", "")).trim(), password = String.valueOf(p.getOrDefault("password", "")), role = String.valueOf(p.getOrDefault("role", "Commuter")).trim(), saId = String.valueOf(p.getOrDefault("saIdNumber", "")).trim(), prdp = String.valueOf(p.getOrDefault("prdpNumber", "")).trim(), company = String.valueOf(p.getOrDefault("companyName", "")).trim();
        if (full.length() < 2 || full.length() > 100) {
            throw new IllegalArgumentException(
                    "Full name must be between 2 and 100 characters."
            );
        }

        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException(
                    "Enter a valid email address."
            );
        }

        if (!phone.matches("^\\+?[0-9]{9,15}$")) {
            throw new IllegalArgumentException(
                    "Enter a valid phone number."
            );
        }
        if (password.length() < 9 || password.length() > 72 || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("Password must be 9-72 characters and contain letters and numbers.");
        }
        User.Role r;
        try {
            r = User.Role.valueOf(role);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid account role.");
        }
        if (r == User.Role.Admin && !allowAdmin) {
            throw new IllegalArgumentException("Administrator accounts cannot be created through public registration.");
        }
        if (users.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException(DUPLICATE_MESSAGE);
        }
        if (users.findByPhoneNumber(phone).isPresent()) {
            throw new IllegalArgumentException(DUPLICATE_MESSAGE);
        }
        if (r == User.Role.Driver && (saId.isBlank() || prdp.isBlank())) {
            throw new IllegalArgumentException("Driver SA ID and PRDP numbers are required.");
        }
        if (r == User.Role.Driver && !saId.matches("^[0-9]{13}$")) {
            throw new IllegalArgumentException("South African ID number must contain exactly 13 digits.");
        }
        if (r == User.Role.FleetOwner && company.length() < 2) {
            throw new IllegalArgumentException("Fleet/company name is required.");
        }
        if (r == User.Role.Driver) {
            if (drivers.findBySaIdNumber(saId).isPresent()) {
                throw new IllegalArgumentException(DUPLICATE_MESSAGE);
            }
            if (drivers.findByPrdpNumber(prdp).isPresent()) {
                throw new IllegalArgumentException(DUPLICATE_MESSAGE);
            }
        }
        User u = new User();
        u.setFullName(full);
        u.setEmail(email);
        u.setPhoneNumber(phone);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(r);
        u.setCreatedAt(LocalDateTime.now());
        u = users.save(u);
        Wallet w = new Wallet();
        w.setUser(u);
        w.setBalance(BigDecimal.ZERO);
        w.setWalletStatus(Wallet.WalletStatus.Active);
        w.setCreatedAt(LocalDateTime.now());
        wallets.save(w);
        if (r == User.Role.Driver) {
            Driver d = new Driver();
            d.setUser(u);
            d.setSaIdNumber(saId);
            d.setPrdpNumber(prdp);
            d.setKycStatus(Driver.KycStatus.Pending);
            drivers.save(d);
        }
        if (r == User.Role.FleetOwner) {
            FleetOwner o = new FleetOwner();
            o.setUser(u);
            o.setCompanyName(company);
            owners.save(o);
        }
        return userDto(u);
    }

    @PostMapping("/auth/register")
    public ResponseEntity<?> authRegister(@RequestBody Map<String, Object> p) {
        try {
            Map<String, Object> user = txTemplate.execute(status -> register(p, false));
            User u = users.findByEmail(String.valueOf(user.get("email"))).orElseThrow();
            String token=sessions.create(u);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
                    .body(map("user", user, "token", token, "expiresInSeconds", 28800));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(map("message", duplicateConstraintMessage(e)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(map("message", safeMessage(e)));
        }
    }

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(@RequestAttribute(value = "authUserId", required = false) Integer authUserId) {
        if (authUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(map("message", "Authentication required."));
        }
        return ResponseEntity.ok(map("user", userDto(users.findById(authUserId).orElseThrow())));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String h, @CookieValue(value = "PTH_SESSION", required = false) String cookieToken) {
        if (h != null && h.startsWith("Bearer ")) sessions.revoke(h.substring(7));
        if (cookieToken != null) sessions.revoke(cookieToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                .build();
    }

    private ResponseCookie sessionCookie(String token){
        return ResponseCookie.from("PTH_SESSION", token)
                .httpOnly(true).secure(secureSessionCookie).path("/api").maxAge(28800).sameSite("Lax").build();
    }

    private ResponseCookie clearSessionCookie(){
        return ResponseCookie.from("PTH_SESSION", "")
                .httpOnly(true).secure(secureSessionCookie).path("/api").maxAge(0).sameSite("Lax").build();
    }

    @PostMapping("/auth/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, Object> p) {
        String email = String.valueOf(p.getOrDefault("email", "")).trim().toLowerCase();
        if (email.isBlank() || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            return ResponseEntity.badRequest().body(map("message", "Enter a valid email address."));
        }
        Optional<User> account = users.findByEmail(email);
        if (account.isEmpty() || Boolean.FALSE.equals(account.get().getIsActive())) {
            return ResponseEntity.ok(map("message", "If an active account exists for that email, reset instructions will be provided."));
        }
        String code = passwordResets.createCode(email);
        Map<String, Object> response = map("message", "If an active account exists for that email, reset instructions will be provided.");
        // Only expose the code when the operator explicitly enables the local demo mode.
        if (exposePasswordResetCode) {
            response.put("resetCode", code);
            response.put("expiresInMinutes", 15);
            response.put("demoOnly", true);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/auth/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, Object> p) {
        try {
            String email = String.valueOf(p.getOrDefault("email", "")).trim().toLowerCase();
            String code = String.valueOf(p.getOrDefault("code", "")).trim();
            String password = String.valueOf(p.getOrDefault("password", ""));
            if (!password.matches("^(?=.*[A-Za-z])(?=.*[0-9]).{9,72}$")) {
                throw new IllegalArgumentException("Password must be 9-72 characters and contain letters and numbers.");
            }
            if (!passwordResets.verifyAndConsume(email, code)) {
                throw new IllegalArgumentException("The reset code is invalid or has expired.");
            }
            User u = users.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("The reset code is invalid or has expired."));
            u.setPasswordHash(encoder.encode(password));
            users.save(u);
            sessions.revokeAllForUser(u.getUserId());
            return ResponseEntity.ok(map("message", "Password reset successfully. You can now sign in."));
        } catch (IllegalArgumentException e) {
            return errorResponse(e);
        } catch (Exception e) {
            LOG.error("Password reset failed", e);
            return ResponseEntity.status(500).body(map("message", "The password could not be reset. Please try again."));
        }
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> p, jakarta.servlet.http.HttpServletRequest request) {
        String role = String.valueOf(p.getOrDefault("role", "")).trim();
        String email = String.valueOf(p.getOrDefault("email", "")).trim().toLowerCase(Locale.ROOT);
        String password = p.get("password") == null ? "" : String.valueOf(p.get("password"));
        // Field-level feedback: tells the person what is missing, never anything about an account.
        if (role.isBlank()) return ResponseEntity.badRequest().body(map("message", "Please select your account role."));
        if (email.isBlank()) return ResponseEntity.badRequest().body(map("message", "Please enter your email address."));
        if (!email.matches(EMAIL_PATTERN)) return ResponseEntity.badRequest().body(map("message", "Please enter a valid email address."));
        if (password.isEmpty()) return ResponseEntity.badRequest().body(map("message", "Please enter your password."));
        User.Role wantedRole;
        try { wantedRole = User.Role.valueOf(role); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(map("message", "Please select a valid account role.")); }

        String emailKey = "e:" + email, ipKey = "ip:" + request.getRemoteAddr();
        if (isLockedOut(emailKey) || isLockedOut(ipKey)) {
            return ResponseEntity.status(429).body(map("message", "Too many sign-in attempts. Please wait 15 minutes and try again."));
        }
        Optional<User> ou = users.findByEmail(email);
        boolean passwordOk = encoder.matches(password, ou.isPresent() ? ou.get().getPasswordHash() : dummyHash);
        // One identical answer for unknown email, wrong password and wrong role, so nothing about any account is revealed.
        if (ou.isEmpty() || !passwordOk || ou.get().getRole() != wantedRole) {
            recordFailedLogin(emailKey);
            recordFailedLogin(ipKey);
            return ResponseEntity.status(401).body(map("message", BAD_CREDENTIALS));
        }
        User u = ou.get();
        if (Boolean.FALSE.equals(u.getIsActive())) {
            recordFailedLogin(emailKey);
            recordFailedLogin(ipKey);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(map("message", BAD_CREDENTIALS));
        }
        failedLogins.remove(emailKey);
        if (u.getRole() == User.Role.FleetOwner && mfaCode != null && !mfaCode.isBlank()) {
            String challenge = DemoFeatureController.issueOwnerMfa(u.getUserId());
            return ResponseEntity.ok(map("mfaRequired", true, "challengeId", challenge, "message", "Enter your verification code to continue."));
        }
        String token = sessions.create(u);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
                .body(map("user", userDto(u), "token", token, "expiresInSeconds", 28800));
    }

    @GetMapping("/wallets")
    public List<Map<String, Object>> allWallets(@RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Admin);
        return wallets.findAll().stream().map(this::walletDto).toList();
    }

    @GetMapping("/wallets/user/{userId}")
    public Map<String, Object> wallet(@PathVariable Integer userId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (!Objects.equals(userId, authUserId)) {
            requireRole(authRole, User.Role.FleetOwner, User.Role.Admin);
            if (User.Role.FleetOwner.name().equals(authRole)) { FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow(); Driver d=drivers.findByUser_UserId(userId).orElse(null); if(d==null||d.getFleetOwner()==null||!Objects.equals(d.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())) throw new SecurityException("You can only access wallets for drivers in your fleet."); }
        }
        return walletDto(wallets.findByUser_UserId(userId).orElseThrow());
    }

    private Map<String, Object> walletDto(Wallet w) {
        return map("walletId", w.getWalletId(), "userId", w.getUser().getUserId(), "balance", w.getBalance(), "walletStatus", w.getWalletStatus().name(), "createdAt", w.getCreatedAt());
    }

    @PostMapping("/wallets")
    public ResponseEntity<?> createWallet(@RequestBody Map<String, Object> p, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Admin);
            Integer uid = Integer.valueOf(String.valueOf(p.get("userId")));
            if (wallets.findByUser_UserId(uid).isPresent()) {
                return ResponseEntity.ok(walletDto(wallets.findByUser_UserId(uid).get()));
            }
            Wallet w = new Wallet();
            w.setUser(users.findById(uid).orElseThrow());
            w.setBalance(new BigDecimal(String.valueOf(p.getOrDefault("balance", "0"))));
            w.setWalletStatus(Wallet.WalletStatus.valueOf(String.valueOf(p.getOrDefault("walletStatus", "Active"))));
            w.setCreatedAt(LocalDateTime.now());
            return ResponseEntity.status(201).body(walletDto(wallets.save(w)));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @Transactional
    @PostMapping("/wallets/topup")
    public ResponseEntity<?> topup(@RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Commuter);
            Integer uid = Integer.valueOf(String.valueOf(p.get("userId")));
            if (!Objects.equals(uid, authUserId)) throw new SecurityException("You can only top up your own wallet.");
            BigDecimal amount = new BigDecimal(String.valueOf(p.get("amount")));
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Amount must be greater than zero.");
            }
            if (amount.scale() > 2) {
                throw new IllegalArgumentException("Amount can have a maximum of two decimal places.");
            }
            if (amount.compareTo(new BigDecimal("10.00")) < 0) {
                throw new IllegalArgumentException("Minimum top-up amount is R10.00.");
            }
            if (amount.compareTo(new BigDecimal("5000.00")) > 0) {
                throw new IllegalArgumentException("Maximum top-up amount is R5,000.00.");
            }
            Wallet w = wallets.findByUser_UserId(uid).orElseThrow();
            if (w.getWalletStatus() != Wallet.WalletStatus.Active) {
                throw new IllegalArgumentException("Your wallet is not active.");
            }

            String paymentMethod = String.valueOf(p.getOrDefault("paymentMethod", "Bank Card"));
            String reference = "TOPUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

            w.setBalance((w.getBalance() == null ? BigDecimal.ZERO : w.getBalance()).add(amount));
            Wallet saved = wallets.save(w);

            Transaction tx = new Transaction();
            tx.setWallet(saved);
            tx.setAmount(amount);
            tx.setTransactionType(Transaction.TransactionType.TopUp);
            tx.setTransactionStatus(Transaction.TransactionStatus.Completed);
            tx.setTransactionReference(reference);
            tx.setCreatedAt(LocalDateTime.now());
            transactions.save(tx);

            return ResponseEntity.ok(map(
                "message", "Money added to your wallet.",
                "reference", reference,
                "paymentMethod", paymentMethod,
                "amount", amount,
                "wallet", walletDto(saved)
            ));
        } catch (Exception e) {
            markRollback();
            return errorResponse(e);
        }
    }

    @GetMapping("/fleet-owners")
    public List<Map<String, Object>> owners(@RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.FleetOwner);
        if (User.Role.Admin.name().equals(authRole)) return owners.findAll().stream().map(o -> map("fleetOwnerId", o.getFleetOwnerId(), "userId", o.getUser().getUserId(), "companyName", o.getCompanyName())).toList();
        return owners.findByUser_UserId(authUserId).map(o -> List.of(map("fleetOwnerId", o.getFleetOwnerId(), "userId", o.getUser().getUserId(), "companyName", o.getCompanyName()))).orElseGet(List::of);
    }

    @GetMapping("/fleet-owners/{id}")
    public Map<String, Object> owner(@PathVariable Integer id, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.FleetOwner);
        FleetOwner o = owners.findById(id).orElseThrow();
        if (!User.Role.Admin.name().equals(authRole) && !Objects.equals(o.getUser().getUserId(), authUserId)) throw new SecurityException("You can only access your own fleet-owner profile.");
        return map("fleetOwnerId", o.getFleetOwnerId(), "userId", o.getUser().getUserId(), "companyName", o.getCompanyName());
    }

    @PostMapping("/fleet-owners")
    public ResponseEntity<?> createOwner(@RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Admin);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(map("message", "Fleet-owner profiles are created during registration."));
    }

    @GetMapping("/drivers")
    public List<Map<String, Object>> driverList(@RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Commuter, User.Role.Driver, User.Role.FleetOwner, User.Role.Admin);
        if (User.Role.FleetOwner.name().equals(authRole)) {
            FleetOwner owner = owners.findByUser_UserId(authUserId).orElseThrow();
            return drivers.findAll().stream().filter(d -> d.getFleetOwner()!=null && Objects.equals(d.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())).map(this::driverDto).toList();
        }
        if (User.Role.Driver.name().equals(authRole)) return drivers.findByUser_UserId(authUserId).stream().map(this::driverDto).toList();
        if (User.Role.Admin.name().equals(authRole)) return drivers.findAll().stream().map(d -> {
            Map<String, Object> m = driverDto(d);
            m.put("fleetOwnerId", d.getFleetOwner() != null ? d.getFleetOwner().getFleetOwnerId() : null);
            m.put("fleetName", d.getFleetOwner() != null ? d.getFleetOwner().getCompanyName() : null);
            return m;
        }).toList();
        return drivers.findAll().stream().filter(d -> d.getKycStatus()==Driver.KycStatus.Verified).map(this::driverDto).toList();
    }

    @GetMapping("/drivers/{id}")
    public Map<String, Object> driver(@PathVariable Integer id, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        Driver d = drivers.findById(id).orElseThrow();
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(id,authUserId);
        else if (!Objects.equals(d.getUser().getUserId(), authUserId) && !User.Role.Commuter.name().equals(authRole) && !User.Role.Admin.name().equals(authRole)) throw new SecurityException("You cannot access this driver profile.");
        return driverDto(d);
    }

    private Map<String, Object> driverDto(Driver d) {
        return map("driverId", d.getDriverId(), "userId", d.getUser().getUserId(), "fullName", d.getUser().getFullName(), "kycStatus", d.getKycStatus().name());
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicleList(@RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Commuter, User.Role.Driver, User.Role.FleetOwner, User.Role.Admin);
        if (User.Role.FleetOwner.name().equals(authRole)) { FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow(); return vehicles.findAll().stream().filter(v->v.getFleetOwner()!=null&&Objects.equals(v.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())).map(this::vehicleDto).toList(); }
        if (User.Role.Driver.name().equals(authRole)) { Driver d=drivers.findByUser_UserId(authUserId).orElseThrow(); return vehicles.findByDriver_DriverId(d.getDriverId()).stream().map(this::vehicleDto).toList(); }
        if (User.Role.Admin.name().equals(authRole)) return vehicles.findAll().stream().map(this::vehicleDto).toList();
        return vehicles.findAll().stream().filter(v->v.getVehicleStatus()==Vehicle.VehicleStatus.Active && v.getDriver().getKycStatus()==Driver.KycStatus.Verified).map(this::vehicleDto).toList();
    }

    @GetMapping("/vehicles/driver/{driverId}")
    public List<Map<String, Object>> driverVehicles(@PathVariable Integer driverId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(driverId,authUserId);
        else if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(driverId, authUserId);
        return vehicles.findByDriver_DriverId(driverId).stream().map(this::vehicleDto).toList();
    }

    private Map<String, Object> vehicleDto(Vehicle v) {
        return map("vehicleId", v.getVehicleId(), "driverId", v.getDriver().getDriverId(), "driverName", v.getDriver().getUser().getFullName(), "registrationNumber", v.getRegistrationNumber(), "vehicleModel", v.getVehicleModel(), "vehicleStatus", v.getVehicleStatus().name());
    }

    @PostMapping("/vehicles")
    public ResponseEntity<?> createVehicle(@RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Driver, User.Role.FleetOwner, User.Role.Admin);
            Integer driverId = p.get("driverId") == null || String.valueOf(p.get("driverId")).isBlank()
                    ? null : Integer.valueOf(String.valueOf(p.get("driverId")));
            Driver ownerDriver;
            if (User.Role.Admin.name().equals(authRole)) {
                if (driverId == null) throw new IllegalArgumentException("driverId is required for administrator vehicle creation.");
                ownerDriver = drivers.findById(driverId).orElseThrow();
            } else if (User.Role.Driver.name().equals(authRole)) {
                if (driverId == null) {
                    ownerDriver = drivers.findByUser_UserId(authUserId).orElseThrow(() -> new IllegalArgumentException("Driver profile not found."));
                } else {
                    ownerDriver = requireDriverForUser(driverId, authUserId);
                }
            } else {
                if (driverId == null) throw new IllegalArgumentException("driverId is required for Fleet Owner vehicle creation.");
                ownerDriver = drivers.findById(driverId).orElseThrow();
                FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow();
                if(ownerDriver.getFleetOwner()==null || !Objects.equals(ownerDriver.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())) throw new SecurityException("You can only register vehicles for drivers assigned to your fleet.");
            }
            Vehicle v = new Vehicle();
            v.setDriver(ownerDriver);
            v.setFleetOwner(ownerDriver.getFleetOwner());
            v.setRegistrationNumber(String.valueOf(p.get("registrationNumber")));
            v.setVehicleModel(String.valueOf(p.get("vehicleModel")));
            v.setVehicleStatus(Vehicle.VehicleStatus.valueOf(String.valueOf(p.getOrDefault("vehicleStatus", "Active"))));
            return ResponseEntity.status(201).body(vehicleDto(vehicles.save(v)));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @PutMapping("/vehicles/{id}")
    public ResponseEntity<?> updateVehicle(@PathVariable Integer id, @RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Driver, User.Role.FleetOwner, User.Role.Admin);
            Vehicle v = vehicles.findById(id).orElseThrow();
            if (User.Role.Driver.name().equals(authRole)) requireDriverForUser(v.getDriver().getDriverId(),authUserId);
            if (User.Role.FleetOwner.name().equals(authRole)) { FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow(); if(v.getFleetOwner()==null||!Objects.equals(v.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())) throw new SecurityException("You can only update vehicles in your own fleet."); }
            if (p.get("registrationNumber") != null) {
                v.setRegistrationNumber(String.valueOf(p.get("registrationNumber")));
            }
            if (p.get("vehicleModel") != null) {
                v.setVehicleModel(String.valueOf(p.get("vehicleModel")));
            }
            if (p.get("vehicleStatus") != null) {
                v.setVehicleStatus(Vehicle.VehicleStatus.valueOf(String.valueOf(p.get("vehicleStatus"))));
            }
            return ResponseEntity.ok(vehicleDto(vehicles.save(v)));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @PostMapping("/fare-preview")
    public Map<String,Object> coordinateFarePreview(@RequestBody Map<String,Object> p) {
        BigDecimal lat1=new BigDecimal(String.valueOf(p.get("startLatitude"))), lon1=new BigDecimal(String.valueOf(p.get("startLongitude")));
        BigDecimal lat2=new BigDecimal(String.valueOf(p.get("endLatitude"))), lon2=new BigDecimal(String.valueOf(p.get("endLongitude")));
        if(lat1.abs().compareTo(new BigDecimal("90"))>0||lat2.abs().compareTo(new BigDecimal("90"))>0||lon1.abs().compareTo(new BigDecimal("180"))>0||lon2.abs().compareTo(new BigDecimal("180"))>0) throw new IllegalArgumentException("GPS coordinates are outside valid ranges.");
        BigDecimal distance=haversineKm(lat1,lon1,lat2,lon2).setScale(2,java.math.RoundingMode.HALF_UP);
        if(distance.signum()<=0||distance.compareTo(new BigDecimal("1000"))>0) throw new IllegalArgumentException("Trip distance is outside the permitted range.");
        BigDecimal rate=defaultFareRate();
        BigDecimal fare=distance.multiply(rate).max(new BigDecimal("10.00")).setScale(2,java.math.RoundingMode.HALF_UP);
        return map("distanceKm",distance,"fareRate",rate,"fareAmount",fare,"currency","ZAR","calculatedBy","server");
    }

    @GetMapping("/routes/{id}/fare-preview")
    public Map<String,Object> farePreview(@PathVariable Integer id) {
        Route r=routes.findById(id).orElseThrow();
        if (!Boolean.TRUE.equals(r.getIsActive())) throw new IllegalArgumentException("This route is not currently available.");
        if (r.getEstimatedDistanceKm()==null || r.getEstimatedDistanceKm().signum()<=0) throw new IllegalArgumentException("This route has no configured distance yet.");
        BigDecimal rate=r.getFareRate()==null?new BigDecimal("12.00"):r.getFareRate();
        BigDecimal fare=r.getFareType()==Route.FareType.Fixed?rate:r.getEstimatedDistanceKm().multiply(rate);
        return map("routeId",id,"distanceKm",r.getEstimatedDistanceKm(),"fareRate",rate,"fareAmount",fare.setScale(2,java.math.RoundingMode.HALF_UP),"currency","ZAR");
    }

    @GetMapping("/routes")
    public List<Route> routeList(@RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Commuter, User.Role.Driver, User.Role.FleetOwner, User.Role.Admin);
        return routes.findAll().stream().filter(r->Boolean.TRUE.equals(r.getIsActive())||User.Role.Admin.name().equals(authRole)).toList();
    }

    @PostMapping("/routes")
    public ResponseEntity<?> createRoute(@RequestBody Route r, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Admin);
        if (r.getFareRate()==null || r.getFareRate().signum()<=0 || r.getFareRate().compareTo(new BigDecimal("5000"))>0) throw new IllegalArgumentException("Fare rate must be greater than R0 and at most R5,000.");
        if (r.getEstimatedDistanceKm()!=null && (r.getEstimatedDistanceKm().signum()<=0 || r.getEstimatedDistanceKm().compareTo(new BigDecimal("1000"))>0)) throw new IllegalArgumentException("Estimated route distance is outside the permitted range.");
        r.setRouteId(null);
        return ResponseEntity.status(201).body(routes.save(r));
    }

    @GetMapping("/drivers/available")
    public List<Map<String, Object>> availableDrivers(@RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Commuter);
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        for (Shift shift : shifts.findByShiftStatusOrderByStartTimeDesc(Shift.ShiftStatus.Active)) {
            if (shift.getDriver() == null || shift.getVehicle() == null || shift.getVehicle().getVehicleStatus() != Vehicle.VehicleStatus.Active) continue;
            Driver d = shift.getDriver();
            result.putIfAbsent(d.getDriverId(), map(
                    "driverId", d.getDriverId(),
                    "userId", d.getUser().getUserId(),
                    "fullName", d.getUser().getFullName(),
                    "kycStatus", d.getKycStatus().name(),
                    "vehicleId", shift.getVehicle().getVehicleId(),
                    "registrationNumber", shift.getVehicle().getRegistrationNumber(),
                    "vehicleModel", shift.getVehicle().getVehicleModel(),
                    "shiftId", shift.getShiftId(),
                    "qrToken", shift.getQrToken(),
                    "shiftStatus", shift.getShiftStatus().name()));
        }
        return new ArrayList<>(result.values());
    }

    @GetMapping("/drivers/verify")
    public Map<String, Object> verifyDriver(@RequestParam String code, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Commuter);
        String token = code == null ? "" : code.trim();
        if (!token.matches("^PTH-SHIFT:[A-Za-z0-9-]{36}$")) {
            throw new IllegalArgumentException("Invalid or expired PayTaxiHub shift QR code.");
        }
        Shift shift = shifts.findFirstByQrTokenAndShiftStatus(token, Shift.ShiftStatus.Active)
                .orElseThrow(() -> new IllegalArgumentException("This shift QR code is invalid or no longer active."));
        Driver d = shift.getDriver();
        Vehicle v = shift.getVehicle();
        if (d == null || v == null || v.getDriver() == null || !Objects.equals(v.getDriver().getDriverId(), d.getDriverId())) {
            throw new IllegalArgumentException("The QR code no longer matches the assigned driver and vehicle.");
        }
        if (v.getVehicleStatus() != Vehicle.VehicleStatus.Active) throw new IllegalArgumentException("This vehicle is not active.");
        if (d.getKycStatus() != Driver.KycStatus.Verified) throw new IllegalArgumentException("This driver is not KYC verified.");
        return map("verified", true, "driverId", d.getDriverId(), "driverName", d.getUser().getFullName(), "vehicleId", v.getVehicleId(), "registrationNumber", v.getRegistrationNumber(), "vehicleModel", v.getVehicleModel(), "shiftId", shift.getShiftId(), "shiftStatus", shift.getShiftStatus().name());
    }

    @GetMapping("/trips")
    public List<Map<String, Object>> tripList(@RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.FleetOwner, User.Role.Admin);
        if (User.Role.Admin.name().equals(authRole)) return trips.findAll().stream().sorted(Comparator.comparing(Trip::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))).map(this::tripDto).toList();
        FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow();
        return trips.findAll().stream().filter(t->t.getDriver()!=null&&t.getDriver().getFleetOwner()!=null&&Objects.equals(t.getDriver().getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())).sorted(Comparator.comparing(Trip::getStartTime, Comparator.nullsLast(Comparator.reverseOrder()))).map(this::tripDto).toList();
    }

    @GetMapping("/trips/commuter/{userId}")
    public List<Map<String, Object>> commuterTrips(@PathVariable Integer userId, @RequestAttribute("authUserId") Integer authUserId) {
        if (!Objects.equals(userId, authUserId)) throw new SecurityException("You can only access your own trips.");
        return trips.findByCommuter_UserIdOrderByStartTimeDesc(userId).stream().map(this::tripDto).toList();
    }

    @GetMapping("/trips/driver/{driverId}")
    public List<Map<String, Object>> driverTrips(@PathVariable Integer driverId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(driverId,authUserId);
        else if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(driverId, authUserId);
        return trips.findByDriver_DriverIdOrderByStartTimeDesc(driverId).stream().map(this::tripDto).toList();
    }

    private BigDecimal defaultFareRate() {
        return routes.findAll().stream().filter(r->Boolean.TRUE.equals(r.getIsActive()) && r.getFareType()==Route.FareType.Per_KM && r.getFareRate()!=null && r.getFareRate().signum()>0).map(Route::getFareRate).findFirst().orElse(new BigDecimal("12.00"));
    }

    private BigDecimal haversineKm(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double p1=Math.toRadians(lat1.doubleValue()), p2=Math.toRadians(lat2.doubleValue());
        double dp=Math.toRadians(lat2.doubleValue()-lat1.doubleValue()), dl=Math.toRadians(lon2.doubleValue()-lon1.doubleValue());
        double a=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return BigDecimal.valueOf(6371.0088*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a))));
    }

    private Map<String, Object> tripDto(Trip t) {
        String phase = t.getTripStatus() == Trip.TripStatus.Completed ? "COMPLETED" : t.getTripStatus() == Trip.TripStatus.Cancelled ? "CANCELLED" : (t.getStartTime() == null ? "REQUESTED" : "ACTIVE");
        return map("tripId", t.getTripId(), "driverId", t.getDriver() != null ? t.getDriver().getDriverId() : null, "driverName", t.getDriver() != null ? t.getDriver().getUser().getFullName() : null, "commuterId", t.getCommuter() != null ? t.getCommuter().getUserId() : null, "commuterName", t.getCommuter() != null ? t.getCommuter().getFullName() : null, "vehicleId", t.getVehicle() != null ? t.getVehicle().getVehicleId() : null, "registrationNumber", t.getVehicle() != null ? t.getVehicle().getRegistrationNumber() : null, "vehicleModel", t.getVehicle() != null ? t.getVehicle().getVehicleModel() : null, "routeId", t.getRoute() != null ? t.getRoute().getRouteId() : null, "routeName", t.getRoute() != null ? t.getRoute().getRouteName() : null, "startLocation", t.getRoute() != null ? t.getRoute().getStartLocation() : null, "destination", t.getRoute() != null ? t.getRoute().getDestination() : null, "startTime", t.getStartTime(), "endTime", t.getEndTime(), "startLatitude", t.getStartLatitude(), "startLongitude", t.getStartLongitude(), "endLatitude", t.getEndLatitude(), "endLongitude", t.getEndLongitude(), "distanceKm", t.getDistanceKm(), "fareAmount", t.getFareAmount(), "tripStatus", t.getTripStatus().name(), "tripPhase", phase);
    }

    @Transactional
    @PostMapping("/trips")
    public ResponseEntity<?> createTrip(@RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Commuter, User.Role.Driver, User.Role.FleetOwner);
            Integer driverId = Integer.valueOf(String.valueOf(p.get("driverId")));
            Integer commuterId = Integer.valueOf(String.valueOf(p.get("commuterId")));
            Integer vehicleId = Integer.valueOf(String.valueOf(p.get("vehicleId")));
            if (User.Role.Driver.name().equals(authRole)) requireDriverForUser(driverId, authUserId);
            if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(driverId,authUserId);
            if (User.Role.Commuter.name().equals(authRole) && !Objects.equals(commuterId, authUserId)) throw new SecurityException("You can only create trips for your own account.");
            Driver d = drivers.findById(driverId).orElseThrow();
            Vehicle v = vehicles.findById(vehicleId).orElseThrow();
            if (v.getDriver() == null || !Objects.equals(v.getDriver().getDriverId(), driverId)) throw new IllegalArgumentException("Selected vehicle does not belong to the selected driver.");
            if (User.Role.Commuter.name().equals(authRole)) {
                shifts.findFirstByDriver_DriverIdAndShiftStatusOrderByStartTimeDesc(driverId, Shift.ShiftStatus.Active).filter(s -> Objects.equals(s.getVehicle().getVehicleId(), vehicleId)).orElseThrow(() -> new IllegalArgumentException("The selected driver is not available on this vehicle right now."));
                boolean alreadyTravelling = trips.findByCommuter_UserIdOrderByStartTimeDesc(commuterId).stream().anyMatch(x -> x.getTripStatus() == Trip.TripStatus.Active);
                if (alreadyTravelling) throw new IllegalArgumentException("You already have an active or requested trip.");
            }
            Trip t = new Trip();
            t.setDriver(d);
            t.setCommuter(users.findById(commuterId).orElseThrow());
            t.setVehicle(v);
            if (p.get("routeId") != null && !String.valueOf(p.get("routeId")).isBlank()) {
                t.setRoute(routes.findById(Integer.valueOf(String.valueOf(p.get("routeId")))).orElse(null));
            } else if (p.get("destination") != null && !String.valueOf(p.get("destination")).isBlank()) {
                Route r = new Route();
                r.setRouteName("Commuter trip #" + System.currentTimeMillis());
                r.setStartLocation(p.get("pickupLabel") != null ? String.valueOf(p.get("pickupLabel")) : "Current GPS location");
                r.setDestination(String.valueOf(p.get("destination")));
                r.setFareType(Route.FareType.Per_KM);
                r.setFareRate(defaultFareRate());
                r.setIsActive(true);
                t.setRoute(routes.save(r));
            }
            t.setStartLatitude(p.get("startLatitude") != null ? new BigDecimal(String.valueOf(p.get("startLatitude"))) : null);
            t.setStartLongitude(p.get("startLongitude") != null ? new BigDecimal(String.valueOf(p.get("startLongitude"))) : null);
            t.setEndLatitude(p.get("endLatitude") != null ? new BigDecimal(String.valueOf(p.get("endLatitude"))) : null);
            t.setEndLongitude(p.get("endLongitude") != null ? new BigDecimal(String.valueOf(p.get("endLongitude"))) : null);
            BigDecimal requestedDistance;
            if (t.getStartLatitude()!=null && t.getStartLongitude()!=null && t.getEndLatitude()!=null && t.getEndLongitude()!=null) {
                requestedDistance = haversineKm(t.getStartLatitude(), t.getStartLongitude(), t.getEndLatitude(), t.getEndLongitude());
            } else if (t.getRoute()!=null && t.getRoute().getEstimatedDistanceKm()!=null) {
                requestedDistance = t.getRoute().getEstimatedDistanceKm();
            } else {
                throw new IllegalArgumentException("A configured route with an estimated distance or pickup/drop-off GPS coordinates is required; client-submitted distance is not trusted.");
            }
            if (requestedDistance.signum() <= 0 || requestedDistance.compareTo(new BigDecimal("1000")) > 0) throw new IllegalArgumentException("Trip distance is outside the permitted range.");
            requestedDistance = requestedDistance.setScale(2, java.math.RoundingMode.HALF_UP);
            t.setDistanceKm(requestedDistance);
            BigDecimal serverRate = t.getRoute() != null && t.getRoute().getFareRate() != null ? t.getRoute().getFareRate() : new BigDecimal("12.00");
            BigDecimal calculatedFare = t.getRoute()!=null && t.getRoute().getFareType()==Route.FareType.Fixed ? serverRate : requestedDistance.multiply(serverRate).max(new BigDecimal("10.00"));
            t.setFareAmount(calculatedFare.setScale(2, java.math.RoundingMode.HALF_UP));
            t.setTripStatus(Trip.TripStatus.Active);
            t.setStartTime(User.Role.Commuter.name().equals(authRole) ? null : LocalDateTime.now());
            return ResponseEntity.status(201).body(tripDto(trips.save(t)));
        } catch (Exception e) {
            markRollback();
            return errorResponse(e);
        }
    }

    @GetMapping("/trips/driver/{driverId}/requests")
    public List<Map<String, Object>> driverTripRequests(@PathVariable Integer driverId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(driverId,authUserId);
        else if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(driverId, authUserId);
        return trips.findByDriver_DriverIdAndTripStatusOrderByStartTimeAsc(driverId, Trip.TripStatus.Active).stream().filter(t -> t.getStartTime() == null).map(this::tripDto).toList();
    }

    @PutMapping("/trips/{id}/accept")
    public Map<String, Object> acceptTrip(@PathVariable Integer id, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Driver);
        Trip t = trips.findById(id).orElseThrow();
        if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(t.getDriver().getDriverId(), authUserId);
        if (t.getTripStatus() != Trip.TripStatus.Active || t.getStartTime() != null) throw new IllegalArgumentException("This trip request is no longer waiting for acceptance.");
        t.setStartTime(LocalDateTime.now());
        return tripDto(trips.save(t));
    }

    @PutMapping("/trips/{id}/complete")
    public Map<String, Object> completeTrip(@PathVariable Integer id, @RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        Trip t = trips.findById(id).orElseThrow();
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(t.getDriver().getDriverId(),authUserId);
        else if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(t.getDriver().getDriverId(), authUserId);
        if (t.getTripStatus() != Trip.TripStatus.Active || t.getStartTime() == null) throw new IllegalArgumentException("A requested trip must be accepted before it can be completed.");
        t.setEndTime(LocalDateTime.now());
        t.setTripStatus(Trip.TripStatus.Completed);
        if (t.getDistanceKm() == null || t.getDistanceKm().signum() < 0) throw new IllegalArgumentException("A valid server-recorded trip distance is required.");
        BigDecimal rate = t.getRoute() != null && t.getRoute().getFareRate() != null ? t.getRoute().getFareRate() : new BigDecimal("12.00");
        BigDecimal fare = t.getRoute()!=null && t.getRoute().getFareType()==Route.FareType.Fixed ? rate : t.getDistanceKm().multiply(rate).max(new BigDecimal("10.00"));
        t.setFareAmount(fare.setScale(2, java.math.RoundingMode.HALF_UP));
        return tripDto(trips.save(t));
    }

    @PutMapping("/trips/{id}/location")
    public Map<String, Object> updateTripLocation(@PathVariable Integer id, @RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        Trip t = trips.findById(id).orElseThrow();
        if (User.Role.Driver.name().equals(authRole)) requireDriverForUser(t.getDriver().getDriverId(), authUserId);
        else if (User.Role.Commuter.name().equals(authRole)) { if (!Objects.equals(t.getCommuter().getUserId(), authUserId)) throw new SecurityException("You can only update your own trip location."); }
        else if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(t.getDriver().getDriverId(),authUserId);
        else requireRole(authRole, User.Role.Admin);
        t.setEndLatitude(new BigDecimal(String.valueOf(p.get("latitude"))));
        t.setEndLongitude(new BigDecimal(String.valueOf(p.get("longitude"))));
        return tripDto(trips.save(t));
    }

    @Transactional
    @PostMapping("/payments")
    public ResponseEntity<?> payment(@RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Commuter);
            Integer uid = Integer.valueOf(String.valueOf(p.get("userId")));
            if (!Objects.equals(uid, authUserId)) throw new SecurityException("You can only pay from your own wallet.");
            Integer did = Integer.valueOf(String.valueOf(p.get("driverId")));
            Integer tripId = p.get("tripId") != null && !String.valueOf(p.get("tripId")).isBlank() ? Integer.valueOf(String.valueOf(p.get("tripId"))) : null;
            BigDecimal amount = new BigDecimal(String.valueOf(p.get("amount")));
            Trip trip = null;
            if (tripId != null) {
                trip = trips.findById(tripId).orElseThrow();
                if (!Objects.equals(trip.getCommuter().getUserId(), authUserId)) throw new SecurityException("You can only pay for your own trip.");
                if (trip.getTripStatus() != Trip.TripStatus.Completed) throw new IllegalArgumentException("Payment is available after the trip is completed.");
                if (trip.getFareAmount() != null && trip.getFareAmount().compareTo(amount) != 0) throw new IllegalArgumentException("Payment amount must match the completed trip fare.");
                if (!transactions.findByTrip_TripIdOrderByCreatedAtDesc(tripId).isEmpty()) throw new IllegalArgumentException("This trip has already been paid.");
            }
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Amount must be greater than zero.");
            }
            Wallet w = wallets.findByUser_UserId(uid).orElseThrow();
            if (w.getWalletStatus() != Wallet.WalletStatus.Active) throw new IllegalArgumentException("Commuter wallet is not active.");
            if (w.getBalance().compareTo(amount) < 0) {
                throw new IllegalArgumentException("Insufficient wallet balance.");
            }
            Driver d = drivers.findById(did).orElseThrow();
            if (d.getKycStatus() != Driver.KycStatus.Verified) throw new IllegalArgumentException("Driver KYC must be verified before receiving payments.");
            Wallet driverWallet = wallets.findByUser_UserId(d.getUser().getUserId()).orElseThrow(() -> new IllegalArgumentException("Driver wallet not found."));
            if (driverWallet.getWalletStatus() != Wallet.WalletStatus.Active) throw new IllegalArgumentException("Driver wallet is not active.");
            w.setBalance(w.getBalance().subtract(amount));
            driverWallet.setBalance(driverWallet.getBalance().add(amount));
            wallets.save(w);
            wallets.save(driverWallet);
            Transaction t = new Transaction();
            t.setTrip(trip);
            t.setWallet(w);
            t.setFromWallet(w);
            t.setToWallet(driverWallet);
            t.setDriver(d);
            t.setAmount(amount);
            t.setTransactionType(Transaction.TransactionType.Payment);
            t.setTransactionStatus(Transaction.TransactionStatus.Completed);
            t.setTransactionReference("PAY-" + UUID.randomUUID());
            t.setCreatedAt(LocalDateTime.now());
            transactions.save(t);
            DemoFeatureController.notifyUser(d.getUser().getUserId(), "Payment received", "R" + amount.toPlainString() + " was credited to your driver wallet.");
            DemoFeatureController.notifyUser(authUserId, "Payment successful", "Your payment of R" + amount.toPlainString() + " was transferred to the driver.");
            return ResponseEntity.ok(map("message", "Payment completed", "reference", t.getTransactionReference(), "amount", amount, "walletBalance", w.getBalance(), "driverWalletBalance", driverWallet.getBalance()));
        } catch (Exception e) {
            markRollback();
            return errorResponse(e);
        }
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> allTransactions(@RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.FleetOwner, User.Role.Admin);
        Set<Integer> ownerDriverIds = new HashSet<>();
        if (User.Role.FleetOwner.name().equals(authRole)) { FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow(); drivers.findAll().stream().filter(d->d.getFleetOwner()!=null&&Objects.equals(d.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())).forEach(d->ownerDriverIds.add(d.getDriverId())); }
        return transactions.findAll().stream().filter(t->User.Role.Admin.name().equals(authRole)||(t.getDriver()!=null&&ownerDriverIds.contains(t.getDriver().getDriverId()))).sorted(Comparator.comparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).map(t -> map("transactionId", t.getTransactionId(), "amount", t.getAmount(), "transactionType", t.getTransactionType().name(), "transactionStatus", t.getTransactionStatus().name(), "transactionReference", t.getTransactionReference(), "driverId", t.getDriver() != null ? t.getDriver().getDriverId() : null, "createdAt", t.getCreatedAt())).toList();
    }

    @GetMapping("/transactions/wallet/{walletId}")
    public List<Map<String, Object>> walletTransactions(@PathVariable Integer walletId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        Wallet w = wallets.findById(walletId).orElseThrow();
        if (!Objects.equals(w.getUser().getUserId(), authUserId)) {
            requireRole(authRole, User.Role.FleetOwner, User.Role.Admin);
            if (User.Role.FleetOwner.name().equals(authRole)) { FleetOwner owner=owners.findByUser_UserId(authUserId).orElseThrow(); Driver d=drivers.findByUser_UserId(w.getUser().getUserId()).orElse(null); if(d==null||d.getFleetOwner()==null||!Objects.equals(d.getFleetOwner().getFleetOwnerId(),owner.getFleetOwnerId())) throw new SecurityException("You can only access wallet transactions for your fleet."); }
        }
        Map<Integer,Transaction> txById=new LinkedHashMap<>();
        transactions.findByWallet_WalletIdOrderByCreatedAtDesc(walletId).forEach(t->txById.put(t.getTransactionId(),t));
        transactions.findByFromWallet_WalletIdOrToWallet_WalletIdOrderByCreatedAtDesc(walletId,walletId).forEach(t->txById.put(t.getTransactionId(),t));
        return txById.values().stream().sorted(Comparator.comparing(Transaction::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).map(t -> map("transactionId", t.getTransactionId(), "tripId", t.getTrip() != null ? t.getTrip().getTripId() : null, "amount", t.getAmount(), "fromWalletId",t.getFromWallet()!=null?t.getFromWallet().getWalletId():null,"toWalletId",t.getToWallet()!=null?t.getToWallet().getWalletId():null,"transactionType", t.getTransactionType().name(), "transactionStatus", t.getTransactionStatus().name(), "transactionReference", t.getTransactionReference(), "createdAt", t.getCreatedAt())).toList();
    }

    @PostMapping("/sos")
    public ResponseEntity<?> createSos(@RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            Integer uid = p.get("userId") == null ? authUserId : Integer.valueOf(String.valueOf(p.get("userId")));
            if (!Objects.equals(uid, authUserId)) throw new SecurityException("You can only create SOS alerts for your own account.");
            SOSEvent e = new SOSEvent();
            e.setUser(users.findById(uid).orElseThrow());
            Trip trip = null;
            if (p.get("tripId") != null && !String.valueOf(p.get("tripId")).isBlank()) {
                trip = trips.findById(Integer.valueOf(String.valueOf(p.get("tripId")))).orElseThrow();
            } else if (User.Role.Commuter.name().equals(authRole)) {
                trip = trips.findByCommuter_UserIdOrderByStartTimeDesc(authUserId).stream().filter(t -> t.getTripStatus() == Trip.TripStatus.Active).findFirst().orElse(null);
            } else if (User.Role.Driver.name().equals(authRole)) {
                Driver driver = drivers.findByUser_UserId(authUserId).orElse(null);
                if (driver != null) trip = trips.findByDriver_DriverIdOrderByStartTimeDesc(driver.getDriverId()).stream().filter(t -> t.getTripStatus() == Trip.TripStatus.Active).findFirst().orElse(null);
            }
            if (trip != null) {
                boolean commuterOwns = trip.getCommuter() != null && Objects.equals(trip.getCommuter().getUserId(), authUserId);
                boolean driverOwns = trip.getDriver() != null && trip.getDriver().getUser() != null && Objects.equals(trip.getDriver().getUser().getUserId(), authUserId);
                if (!commuterOwns && !driverOwns && !User.Role.FleetOwner.name().equals(authRole)) throw new SecurityException("You can only attach SOS to your own active trip.");
                e.setTrip(trip);
            }
            e.setLatitude(p.get("latitude") != null ? new BigDecimal(String.valueOf(p.get("latitude"))) : null);
            e.setLongitude(p.get("longitude") != null ? new BigDecimal(String.valueOf(p.get("longitude"))) : null);
            e.setSosStatus(SOSEvent.SosStatus.Active);
            e.setCreatedAt(LocalDateTime.now());
            SOSEvent s = sos.save(e);
            return ResponseEntity.status(201).body(map("sosId", s.getSosId(), "sosStatus", s.getSosStatus().name(), "tripId", s.getTrip() != null ? s.getTrip().getTripId() : null, "latitude", s.getLatitude(), "longitude", s.getLongitude(), "createdAt", s.getCreatedAt()));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @GetMapping("/sos")
    public List<Map<String, Object>> allSos(@RequestAttribute("authRole") String authRole) {
        requireRole(authRole, User.Role.Driver, User.Role.FleetOwner, User.Role.Admin);
        return sos.findAll().stream().sorted(Comparator.comparing(SOSEvent::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))).map(s -> map("sosId", s.getSosId(), "userId", s.getUser().getUserId(), "sosStatus", s.getSosStatus().name(), "latitude", s.getLatitude(), "longitude", s.getLongitude(), "createdAt", s.getCreatedAt())).toList();
    }

    @GetMapping("/sos/user/{userId}")
    public List<Map<String, Object>> userSos(@PathVariable Integer userId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (!Objects.equals(userId, authUserId) && !User.Role.Admin.name().equals(authRole)) throw new SecurityException("You can only access your own SOS alerts.");
        return sos.findByUser_UserIdOrderByCreatedAtDesc(userId).stream().map(s -> map("sosId", s.getSosId(), "sosStatus", s.getSosStatus().name(), "latitude", s.getLatitude(), "longitude", s.getLongitude(), "createdAt", s.getCreatedAt())).toList();
    }

    @PostMapping("/shifts")
    public ResponseEntity<?> createShift(@RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        try {
            requireRole(authRole, User.Role.Driver, User.Role.FleetOwner);
            Integer driverId = Integer.valueOf(String.valueOf(p.get("driverId")));
            Driver d = User.Role.Driver.name().equals(authRole) ? requireDriverForUser(driverId, authUserId) : (User.Role.FleetOwner.name().equals(authRole) ? requireDriverForOwner(driverId,authUserId) : drivers.findById(driverId).orElseThrow());
            if (d.getKycStatus() != Driver.KycStatus.Verified) throw new SecurityException("Your driver KYC must be approved before starting a shift.");
            if (shifts.findFirstByDriver_DriverIdAndShiftStatusOrderByStartTimeDesc(driverId, Shift.ShiftStatus.Active).isPresent()) {
                throw new IllegalArgumentException("You already have an active shift. End it before starting another one.");
            }
            Vehicle vehicle = vehicles.findById(Integer.valueOf(String.valueOf(p.get("vehicleId")))).orElseThrow();
            if (vehicle.getDriver() == null || !Objects.equals(vehicle.getDriver().getDriverId(), d.getDriverId())) throw new IllegalArgumentException("This vehicle is not assigned to this driver.");
            if (vehicle.getVehicleStatus() != Vehicle.VehicleStatus.Active) throw new IllegalArgumentException("Select an active vehicle before starting your shift.");
            Shift s = new Shift();
            s.setDriver(d);
            s.setVehicle(vehicle);
            s.setStartTime(LocalDateTime.now());
            s.setTotalEarnings(BigDecimal.ZERO);
            s.setOwnerTarget(p.get("ownerTarget") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(p.get("ownerTarget"))));
            if (s.getOwnerTarget().signum() < 0) throw new IllegalArgumentException("Owner target cannot be negative.");
            s.setShiftStatus(Shift.ShiftStatus.Active);
            s = shifts.save(s);
            s.setQrToken("PTH-SHIFT:" + UUID.randomUUID());
            return ResponseEntity.status(201).body(shiftDto(shifts.save(s)));
        } catch (Exception e) {
            return errorResponse(e);
        }
    }

    @GetMapping("/shifts/driver/{driverId}")
    public List<Map<String, Object>> driverShifts(@PathVariable Integer driverId, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(driverId,authUserId);
        else if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(driverId, authUserId);
        return shifts.findByDriver_DriverIdOrderByStartTimeDesc(driverId).stream().map(this::shiftDto).toList();
    }

    private Map<String, Object> shiftDto(Shift s) {
        return map("shiftId", s.getShiftId(), "driverId", s.getDriver().getDriverId(), "vehicleId", s.getVehicle().getVehicleId(), "registrationNumber", s.getVehicle().getRegistrationNumber(), "vehicleModel", s.getVehicle().getVehicleModel(), "driverName", s.getDriver().getUser().getFullName(), "startTime", s.getStartTime(), "endTime", s.getEndTime(), "totalEarnings", s.getTotalEarnings(), "ownerTarget", s.getOwnerTarget(), "qrToken", s.getQrToken(), "shiftStatus", s.getShiftStatus().name());
    }

    @PutMapping("/shifts/{id}/complete")
    public Map<String, Object> completeShift(@PathVariable Integer id, @RequestBody Map<String, Object> p, @RequestAttribute("authUserId") Integer authUserId, @RequestAttribute("authRole") String authRole) {
        Shift s = shifts.findById(id).orElseThrow();
        if (User.Role.FleetOwner.name().equals(authRole)) requireDriverForOwner(s.getDriver().getDriverId(),authUserId);
        else if (!User.Role.Admin.name().equals(authRole)) requireDriverForUser(s.getDriver().getDriverId(), authUserId);
        if (s.getShiftStatus() != Shift.ShiftStatus.Active) throw new IllegalArgumentException("This shift has already been completed.");
        s.setEndTime(LocalDateTime.now());
        s.setShiftStatus(Shift.ShiftStatus.Completed);
        BigDecimal reconciled = trips.findByDriver_DriverIdOrderByStartTimeDesc(s.getDriver().getDriverId()).stream()
            .filter(t -> t.getTripStatus() == Trip.TripStatus.Completed && t.getStartTime() != null && !t.getStartTime().isBefore(s.getStartTime()) && !t.getStartTime().isAfter(s.getEndTime()))
            .map(t -> t.getFareAmount() == null ? BigDecimal.ZERO : t.getFareAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        s.setTotalEarnings(reconciled);
        return shiftDto(shifts.save(s));
    }
}
