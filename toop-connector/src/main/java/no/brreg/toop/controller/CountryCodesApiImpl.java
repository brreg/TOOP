package no.brreg.toop.controller;

// This code is Public Domain. See LICENSE

import no.brreg.toop.caches.CountryCodeCache;
import no.brreg.toop.generated.model.CountryCode;
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
public class CountryCodesApiImpl implements no.brreg.toop.generated.api.CountrycodesApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(CountryCodesApiImpl.class);

    @Autowired
    private CountryCodeCache countryCodeCache;


    @Override
    public ResponseEntity<List<CountryCode>> getCountryCodes(HttpServletRequest httpServletRequest, HttpServletResponse response) {
        try {
            List<CountryCode> countryCodes = countryCodeCache.getCountryCodes();
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

}
