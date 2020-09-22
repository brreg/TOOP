package no.brreg.toop.controller;

import no.brreg.toop.CountryCodeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;


@Controller
@RestControllerAdvice
public class QueryApiImpl implements no.brreg.toop.generated.api.QueryApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueryApiImpl.class);

    @Autowired
    private CountryCodeCache countryCodeCache;


    @Override
    public ResponseEntity<List<String>> getCountryCodes(HttpServletRequest httpServletRequest, HttpServletResponse response) {
        try {
            List<String> countryCodes = countryCodeCache.getCountryCodes();
            if (countryCodes==null || countryCodes.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            } else {
                return new ResponseEntity<>(countryCodes, HttpStatus.OK);
            }
        } catch (Exception e) {
            LOGGER.error("getCountryCodes failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseEntity<List<String>> getOrgno(HttpServletRequest httpServletRequest, HttpServletResponse response, String countrycode, String orgno) {
        try {
            if (true) {
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            } else {
                return new ResponseEntity<>(Collections.singletonList("toop"), HttpStatus.OK);
            }
        } catch (Exception e) {
            LOGGER.error("getOrgno failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
