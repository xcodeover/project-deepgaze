package com.deepgaze.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Installs a JVM-wide default uncaught-exception handler that silences the
 * specific "SSE client already gone" race that Spring's ReactiveTypeHandler
 * leaks onto its reactive task executor — those stack traces are printed by
 * the JVM directly to stderr and therefore bypass logback entirely (the
 * TurboFilter cannot touch them).
 *
 * Only two exception signatures are silenced, both tied to an already-dead
 * async context on Tomcat. Any other uncaught exception is forwarded to the
 * previously-installed handler so real bugs still surface.
 */
@Slf4j
@Component
public class UncaughtExceptionNoiseSilencer {

    @PostConstruct
    void install() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isSseClientGone(throwable)) return;
            if (prev != null) prev.uncaughtException(thread, throwable);
            else throwable.printStackTrace();
        });
        log.debug("UncaughtExceptionNoiseSilencer installed");
    }

    private static boolean isSseClientGone(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String cls = c.getClass().getName();
            String msg = c.getMessage();
            if (cls.equals("java.lang.IllegalStateException") && msg != null
                    && msg.contains("AsyncListener.onError()")) return true;
            if (cls.equals("org.springframework.web.context.request.async.AsyncRequestNotUsableException")) return true;
            if (cls.equals("org.apache.catalina.connector.ClientAbortException")) return true;
            if (cls.equals("java.io.IOException") && msg != null && msg.contains("Broken pipe")) return true;
        }
        return false;
    }
}
