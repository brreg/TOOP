package no.brreg.toop.controller;

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
import java.util.List;
import java.util.concurrent.TimeoutException;


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
    public ResponseEntity<Enhet> getOrganization(HttpServletRequest httpServletRequest, HttpServletResponse response, String countrycode, String orgno) {
        try {
            final Enhet enhet = brregIncomingHandler.getOrganization(countrycode, orgno);
            if (enhet == null) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            } else {
                return new ResponseEntity<>(enhet, HttpStatus.OK);
            }
        } catch (TimeoutException e) {
            LOGGER.error("getOrganization timed out while fetching "+countrycode+"/"+orgno);
            return new ResponseEntity<>(HttpStatus.REQUEST_TIMEOUT);
        } catch (Exception e) {
            LOGGER.error("getOrganization failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
