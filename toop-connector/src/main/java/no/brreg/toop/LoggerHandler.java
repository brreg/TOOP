package no.brreg.toop;

// This code is Public Domain. See LICENSE

import no.brreg.toop.generated.model.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;


@Component
public class LoggerHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerHandler.class);

    public enum Level {INFO, ERROR, DEBUG}

    private static final int MAX_LOGS = 100;

    private static final List<Log> logs = new ArrayList<>();
    private static final Object logsLock = new Object();


    public void log(final Level level, final String message) {
        log(level, message, null);
    }

    public void log(final Level level, final String message, final Throwable throwable) {
        if (level == Level.INFO) {
            LOGGER.info(message, throwable);
        } else if (level == Level.ERROR) {
            LOGGER.error(message, throwable);
        } else if (level == Level.DEBUG) {
            LOGGER.debug(message, throwable);
        }

        synchronized (LoggerHandler.logsLock) {
            while (LoggerHandler.logs.size() >= MAX_LOGS) {
                LoggerHandler.logs.remove(0);
            }

            Log log = new Log();
            log.setTime(OffsetDateTime.now());
            if (level != null) {
                log.setSeverity(level.name());
            }
            log.setMessage(message);
            LoggerHandler.logs.add(log);
        }
    }

    public List<Log> getLogs() {
        return LoggerHandler.logs;
    }
}
