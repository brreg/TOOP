package no.brreg.toop.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


@Controller
@RestControllerAdvice
public class Phase4ApiImpl implements no.brreg.toop.generated.api.Phase4Api {
    private static final Logger LOGGER = LoggerFactory.getLogger(Phase4ApiImpl.class);

    @Override
    public ResponseEntity<String> phase4Callback(HttpServletRequest httpServletRequest, HttpServletResponse response) {
        try {
            if (true) {
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            } else {
                return new ResponseEntity<>("toop", HttpStatus.OK);
            }
        } catch (Exception e) {
            LOGGER.error("phase4Callback failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
