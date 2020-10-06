package no.brreg.toop.controller;

// This code is Public Domain. See LICENSE

import no.brreg.toop.BrregIncomingHandler;
import no.brreg.toop.CountryCodeCache;
import no.brreg.toop.generated.model.CountryCode;
import no.brreg.toop.generated.model.Enhet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Controller
@RestControllerAdvice
public class QueryApiImpl implements no.brreg.toop.generated.api.QueryApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueryApiImpl.class);

    @Autowired
    private CountryCodeCache countryCodeCache;

    @Autowired
    private BrregIncomingHandler brregIncomingHandler;


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

    @Override
    public ResponseEntity<Enhet> getByLegalPerson(HttpServletRequest httpServletRequest, HttpServletResponse response, String countrycode, String legalperson) {
        try {
            final BrregIncomingHandler.ToopResponse toopResponse = brregIncomingHandler.getByIdentifier(countrycode, legalperson, new HashMap<>(), true);
            HttpStatus status = toopResponse==null ? HttpStatus.NOT_FOUND : toopResponse.getStatus();
            final String errorMessage = toopResponse==null ? null : toopResponse.getErrorMessage();
            if (status == HttpStatus.OK && toopResponse.getEnhet()==null) {
                status = HttpStatus.NOT_FOUND;
            }

            if (status == HttpStatus.OK) {
                return new ResponseEntity<>(toopResponse.getEnhet(), HttpStatus.OK);
            } else {
                LOGGER.info("Status: "+status.value());
                if (errorMessage!=null && !errorMessage.isEmpty()) {
                    LOGGER.info("ErrorMsg: "+errorMessage);
                    response.sendError(status.value(), errorMessage);
                }
                return new ResponseEntity<>(status);
            }
        } catch (Exception e) {
            LOGGER.error("getByLegalPerson failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseEntity<Enhet> getByNaturalPerson(HttpServletRequest httpServletRequest, HttpServletResponse response,
                                                    String countrycode, String naturalperson, String firstname, String lastname, LocalDate birthdate) {
        try {
            Map<String,Object> properties = new HashMap<>();
            if (firstname!=null && !firstname.isEmpty()) {
                properties.put("firstname", firstname);
            }
            if (lastname!=null && !lastname.isEmpty()) {
                properties.put("lastname", lastname);
            }
            if (birthdate!=null) {
                properties.put("birthdate", birthdate);
            }
            final BrregIncomingHandler.ToopResponse toopResponse = brregIncomingHandler.getByIdentifier(countrycode, naturalperson, properties, false);
            HttpStatus status = toopResponse==null ? HttpStatus.NOT_FOUND : toopResponse.getStatus();
            final String errorMessage = toopResponse==null ? null : toopResponse.getErrorMessage();
            if (status == HttpStatus.OK && toopResponse.getEnhet()==null) {
                status = HttpStatus.NOT_FOUND;
            }

            if (status == HttpStatus.OK) {
                return new ResponseEntity<>(toopResponse.getEnhet(), HttpStatus.OK);
            } else {
                LOGGER.info("Status: "+status.value());
                if (errorMessage!=null && !errorMessage.isEmpty()) {
                    LOGGER.info("ErrorMsg: "+errorMessage);
                    response.sendError(status.value(), errorMessage);
                }
                return new ResponseEntity<>(status);
            }
        } catch (Exception e) {
            LOGGER.error("getByLegalPerson failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
