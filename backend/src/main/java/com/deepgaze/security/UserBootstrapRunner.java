package com.deepgaze.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the first admin account on a cold start, so an operator can log in
 * without ever touching the filesystem. Runs exactly once: if the users
 * table already has any rows we skip, preserving whatever credentials the
 * operator has rotated to since.
 *
 * Credentials come from env (or application.yml):
 *   DEEPGAZE_ADMIN_USERNAME   default "admin"
 *   DEEPGAZE_ADMIN_PASSWORD   REQUIRED on first boot; omission is fatal so
 *                             we never ship a default password
 *
 * This is deliberately strict — the cost of hard-failing with a clear error
 * is much lower than shipping a widely-known default credential.
 */
@Slf4j
@Component
public class UserBootstrapRunner implements CommandLineRunner {

    private final UserStore store;
    private final BCryptPasswordEncoder encoder;
    private final String username;
    private final String password;

    public UserBootstrapRunner(
            UserStore store,
            BCryptPasswordEncoder encoder,
            @Value("${deepgaze.admin.username:admin}") String username,
            @Value("${deepgaze.admin.password:}")     String password
    ) {
        this.store = store;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (store.count() > 0) {
            log.info("UserBootstrapRunner: {} user(s) already exist — skipping seed", store.count());
            return;
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "No users found in the database and DEEPGAZE_ADMIN_PASSWORD is not set. " +
                    "Set DEEPGAZE_ADMIN_PASSWORD (and optionally DEEPGAZE_ADMIN_USERNAME) " +
                    "on first boot to seed the initial admin account.");
        }
        store.insert(username, encoder.encode(password), "ADMIN");
        log.warn("UserBootstrapRunner: seeded initial ADMIN account '{}'. " +
                 "Rotate the password via the UI / API after first login.", username);
    }
}
