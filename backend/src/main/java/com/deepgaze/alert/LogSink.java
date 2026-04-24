package com.deepgaze.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Zero-config baseline sink — every deployment gets an audit trail in the
 * backend log even when no webhook is wired up.
 */
@Slf4j
@Component
public class LogSink implements NotificationSink {

    @Override
    public String id() {
        return "log";
    }

    @Override
    public void deliver(AlertEvent event) {
        switch (event.kind()) {
            case FIRED -> log.warn("[ALERT-FIRED] rule={} target={} severity={} value={} msg=\"{}\"",
                    event.ruleId(), event.targetId(), event.severity(), event.value(), event.message());
            case RESOLVED -> log.info("[ALERT-RESOLVED] rule={} target={} msg=\"{}\"",
                    event.ruleId(), event.targetId(), event.message());
        }
    }
}
