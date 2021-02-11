package no.brreg.toop.controller;

import no.brreg.toop.caches.CountryCodeCache;
import no.brreg.toop.generated.model.CountryCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;


@Controller
@RestControllerAdvice
public class HealthController {

    @Autowired
    private CountryCodeCache countryCodeCache;

    
    @GetMapping(value="/ping", produces={"text/plain"})
    public ResponseEntity<String> getPing() {
        return ResponseEntity.ok("pong");
    }

    @GetMapping(value="/ready")
    public ResponseEntity<Void> getReady() {
        List<CountryCode> countryCodes = countryCodeCache.getCountryCodes();
        if (countryCodes!=null && !countryCodes.isEmpty()) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

}
