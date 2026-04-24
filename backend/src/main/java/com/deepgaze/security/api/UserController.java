package com.deepgaze.security.api;

import com.deepgaze.security.UserRow;
import com.deepgaze.security.UserStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin surface for user management.
 *
 *   GET    /api/users                        list (ADMIN)
 *   POST   /api/users                        create (ADMIN)
 *   DELETE /api/users/{id}                   delete (ADMIN)
 *   PUT    /api/users/{id}/password          change another user's password (ADMIN)
 *   PUT    /api/users/{id}/role              change role (ADMIN)
 *   PUT    /api/users/{id}/enabled           enable / disable (ADMIN)
 *   PUT    /api/users/me/password            change MY password (any auth'd user)
 *
 * Safety rails:
 *   - Username is lowercased and must be slug-ish.
 *   - Role must be in {@link #ALLOWED_ROLES}. "VIEWER" / "OPERATOR" exist for
 *     future authorisation checks; today everyone with a session can hit
 *     every non-/api/users route, but recording role now means we don't have
 *     to migrate data later.
 *   - We refuse to delete or disable the last enabled ADMIN — otherwise a
 *     misclick locks everyone out and the only recovery is a shell + sqlite3.
 *   - A user cannot delete / disable / demote THEMSELVES; asking someone else
 *     with admin privileges to do it is safer than a single-click footgun.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");
    private static final int PASSWORD_MIN = 8;

    private final UserStore store;
    private final BCryptPasswordEncoder encoder;

    public UserController(UserStore store, BCryptPasswordEncoder encoder) {
        this.store = store;
        this.encoder = encoder;
    }

    /* ---------- admin endpoints ---------- */

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserDto> list() {
        return store.findAll().stream().map(UserDto::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(@RequestBody CreateUserRequest body) {
        String username = requireUsername(body == null ? null : body.username());
        String password = requirePassword(body == null ? null : body.password());
        String role     = normaliseRole(body == null ? null : body.role());
        if (store.findByUsername(username) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "Username already exists"));
        }
        store.insert(username, encoder.encode(password), role);
        UserRow row = store.findByUsername(username);
        log.info("User created: username='{}' role='{}'", username, role);
        return ResponseEntity.status(201).body(UserDto.of(row));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable long id) {
        String me = currentUsername();
        UserRow target = store.findById(id);
        if (target == null) return ResponseEntity.notFound().build();
        if (target.username().equalsIgnoreCase(me)) {
            return ResponseEntity.status(400).body(Map.of("error", "You cannot delete your own account"));
        }
        if ("ADMIN".equals(target.role()) && target.enabled() && store.countEnabledAdmins() <= 1) {
            return ResponseEntity.status(400).body(Map.of("error", "Cannot delete the last enabled ADMIN"));
        }
        boolean removed = store.deleteById(id);
        log.info("User deleted: id={} username='{}' by='{}'", id, target.username(), me);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resetPassword(@PathVariable long id, @RequestBody PasswordRequest body) {
        String password = requirePassword(body == null ? null : body.newPassword());
        UserRow target = store.findById(id);
        if (target == null) return ResponseEntity.notFound().build();
        store.updatePassword(id, encoder.encode(password));
        log.info("Password reset for user='{}' (admin action)", target.username());
        return ResponseEntity.ok(UserDto.of(store.findById(id)));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateRole(@PathVariable long id, @RequestBody RoleRequest body) {
        String role = normaliseRole(body == null ? null : body.role());
        String me = currentUsername();
        UserRow target = store.findById(id);
        if (target == null) return ResponseEntity.notFound().build();
        if (target.username().equalsIgnoreCase(me) && !"ADMIN".equals(role)) {
            return ResponseEntity.status(400).body(Map.of("error", "You cannot demote your own account"));
        }
        if ("ADMIN".equals(target.role()) && !"ADMIN".equals(role)
                && target.enabled() && store.countEnabledAdmins() <= 1) {
            return ResponseEntity.status(400).body(Map.of("error", "Cannot demote the last enabled ADMIN"));
        }
        store.updateRole(id, role);
        log.info("Role changed: user='{}' role='{}' by='{}'", target.username(), role, me);
        return ResponseEntity.ok(UserDto.of(store.findById(id)));
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateEnabled(@PathVariable long id, @RequestBody EnabledRequest body) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.enabled());
        String me = currentUsername();
        UserRow target = store.findById(id);
        if (target == null) return ResponseEntity.notFound().build();
        if (target.username().equalsIgnoreCase(me) && !enabled) {
            return ResponseEntity.status(400).body(Map.of("error", "You cannot disable your own account"));
        }
        if (!enabled && "ADMIN".equals(target.role())
                && target.enabled() && store.countEnabledAdmins() <= 1) {
            return ResponseEntity.status(400).body(Map.of("error", "Cannot disable the last enabled ADMIN"));
        }
        store.updateEnabled(id, enabled);
        log.info("User {}: user='{}' by='{}'", enabled ? "enabled" : "disabled", target.username(), me);
        return ResponseEntity.ok(UserDto.of(store.findById(id)));
    }

    /* ---------- self-service ---------- */

    @PutMapping("/me/password")
    public ResponseEntity<?> changeMyPassword(@RequestBody ChangePasswordRequest body) {
        if (body == null || body.currentPassword() == null || body.newPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "currentPassword and newPassword are required"));
        }
        String newPwd;
        try {
            newPwd = requirePassword(body.newPassword());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        String me = currentUsername();
        UserRow row = store.findByUsername(me);
        if (row == null || !row.enabled()) {
            return ResponseEntity.status(401).body(Map.of("error", "Session is no longer valid"));
        }
        if (!encoder.matches(body.currentPassword(), row.passwordHash())) {
            return ResponseEntity.status(400).body(Map.of("error", "Current password is incorrect"));
        }
        store.updatePassword(row.id(), encoder.encode(newPwd));
        log.info("Password changed by user='{}'", row.username());
        return ResponseEntity.noContent().build();
    }

    /* ---------- helpers ---------- */

    private static String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : String.valueOf(auth.getPrincipal());
    }

    private static String requireUsername(String raw) {
        if (raw == null) throw new IllegalArgumentException("username is required");
        String u = raw.trim().toLowerCase();
        if (u.isEmpty()) throw new IllegalArgumentException("username is required");
        if (u.length() > 64) throw new IllegalArgumentException("username too long (>64)");
        if (!u.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("username must match [a-z0-9][a-z0-9._-]*");
        }
        return u;
    }

    private static String requirePassword(String raw) {
        if (raw == null || raw.length() < PASSWORD_MIN) {
            throw new IllegalArgumentException("password must be at least " + PASSWORD_MIN + " characters");
        }
        return raw;
    }

    private static String normaliseRole(String raw) {
        if (raw == null || raw.isBlank()) return "VIEWER";
        String r = raw.trim().toUpperCase();
        if (!ALLOWED_ROLES.contains(r)) {
            throw new IllegalArgumentException("role must be one of " + ALLOWED_ROLES);
        }
        return r;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    public record CreateUserRequest(String username, String password, String role) {}
    public record PasswordRequest(String newPassword) {}
    public record RoleRequest(String role) {}
    public record EnabledRequest(Boolean enabled) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
