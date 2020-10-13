package no.brreg.toop.controller;

// This code is Public Domain. See LICENSE

import no.brreg.toop.LoggerHandler;
import no.brreg.toop.generated.model.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;


@Controller
@RestControllerAdvice
public class LogApiImpl implements no.brreg.toop.generated.api.LogApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogApiImpl.class);

    @Autowired
    private LoggerHandler loggerHandler;


    @Override
    public ResponseEntity<List<Log>> getLog(HttpServletRequest httpServletRequest, HttpServletResponse response) {
        try {
            final List<Log> logEntries = loggerHandler.getLogs();
            if (logEntries==null || logEntries.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            } else {
                return new ResponseEntity<>(logEntries, HttpStatus.OK);
            }
        } catch (Exception e) {
            LOGGER.error("getLog failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
