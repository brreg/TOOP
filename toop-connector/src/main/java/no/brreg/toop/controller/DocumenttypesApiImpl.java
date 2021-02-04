package no.brreg.toop.controller;

// This code is Public Domain. See LICENSE

import no.brreg.toop.caches.CountryCodeCache;
import no.brreg.toop.generated.model.CountryCodeDocType;
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
public class DocumenttypesApiImpl implements no.brreg.toop.generated.api.DocumenttypesApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumenttypesApiImpl.class);

    @Autowired
    private CountryCodeCache countryCodeCache;


    @Override
    public ResponseEntity<List<CountryCodeDocType>> getCountryDocumentTypes(HttpServletRequest httpServletRequest, HttpServletResponse response, String countrycode) {
        try {
            List<CountryCodeDocType> docTypes = countryCodeCache.getDocumentTypes(countrycode);
            if (docTypes==null || docTypes.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            } else {
                return new ResponseEntity<>(docTypes, HttpStatus.OK);
            }
        } catch (Exception e) {
            LOGGER.error("getCountryDocumentTypes failed: ", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
