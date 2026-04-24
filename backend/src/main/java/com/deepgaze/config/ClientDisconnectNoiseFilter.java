package com.deepgaze.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Silences log events whose cause chain reports a dead SSE client.
 *
 * SSE clients (browser tabs) routinely close the TCP socket mid-stream on
 * tab-close, refresh, nav-away or laptop-lid events. Spring's
 * ReactiveTypeHandler and Tomcat's dispatcher both try to flush the next
 * event to that dead socket and log a full stack — expected operational
 * noise, not an application bug, but it drowns real errors.
 *
 * This filter is narrow on purpose: it matches only two specific throwable
 * classes AND the "Broken pipe" IOException message. Anything else (real
 * NPE, SQL failure, JSON mapping error) still reaches the console.
 */
public class ClientDisconnectNoiseFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (t == null) return FilterReply.NEUTRAL;
        for (Throwable c = t; c != null; c = c.getCause()) {
            String cls = c.getClass().getName();
            if (cls.equals("org.springframework.web.context.request.async.AsyncRequestNotUsableException")) {
                return FilterReply.DENY;
            }
            if (cls.equals("org.apache.catalina.connector.ClientAbortException")) {
                return FilterReply.DENY;
            }
            if (cls.equals("java.io.IOException")) {
                String msg = c.getMessage();
                if (msg != null && msg.contains("Broken pipe")) return FilterReply.DENY;
            }
            if (cls.equals("java.lang.IllegalStateException")) {
                String msg = c.getMessage();
                if (msg != null && msg.contains("AsyncListener.onError()")) return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
