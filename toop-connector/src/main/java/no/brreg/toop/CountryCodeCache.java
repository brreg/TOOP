package no.brreg.toop;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import no.brreg.toop.generated.model.CountryCode;
import no.brreg.toop.generated.model.CountryCodeMatch;
import no.brreg.toop.generated.model.CountryCodeParticipantEntity;
import no.brreg.toop.generated.model.CountryCodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAmount;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


@Component
public class CountryCodeCache {
    private static Logger LOGGER = LoggerFactory.getLogger(CountryCodeCache.class);

    private static final String COUNTRY_LOOKUP = "https://directory.acc.exchange.toop.eu/search/1.0/json?doctype=toop-doctypeid-qns%3A%3ARegisteredOrganization%3A%3AREGISTERED_ORGANIZATION_TYPE%3A%3ACONCEPT%23%23CCCEV%3A%3Atoop-edm%3Av2.0";
    private static final String COUNTRY_SCHEME = "iso6523-actorid-upis";

    private LocalDateTime cacheTime = null;
    private static final TemporalAmount CACHE_VALID_DURATION = Duration.ofHours(12);
    private AtomicBoolean isUpgradingCache = new AtomicBoolean(false);

    private static Map<String,CountryCode> countryCodes = new HashMap<>();
    private static Object countryCodesLock = new Object();


    public void update() {
        LOGGER.info("Updating CountryCode cache");
        if (cacheTime!=null && cacheTime.plus(CACHE_VALID_DURATION).isAfter(LocalDateTime.now())) {
            return; //Cache is still valid
        }

        if (isUpgradingCache.compareAndSet(false, true)) {
            try {
                HttpResponse<CountryCodeResult> countryLookupResponse = Unirest.get(COUNTRY_LOOKUP)
                        .header("accept", "application/json")
                        .asObject(CountryCodeResult.class);
                int status = countryLookupResponse.getStatus();
                if (status >= 200 && status <= 299) {
                    CountryCodeResult countryCodeResult = countryLookupResponse.getBody();
                    synchronized(countryCodesLock) {
                        countryCodes.clear();
                        for (CountryCodeMatch countryCodeMatch : countryCodeResult.getMatches()) {
                            if (countryCodeMatch!=null &&
                                countryCodeMatch.getParticipantID()!=null &&
                                COUNTRY_SCHEME.equals(countryCodeMatch.getParticipantID().getScheme()) &&
                                countryCodeMatch.getParticipantID().getValue()!=null &&
                                !countryCodeMatch.getParticipantID().getValue().isEmpty() &&
                                countryCodeMatch.getEntities()!=null &&
                                !countryCodeMatch.getEntities().isEmpty()) {
                                for (CountryCodeParticipantEntity entity : countryCodeMatch.getEntities()) {
                                    if (entity!=null &&
                                        entity.getCountryCode()!=null &&
                                        !entity.getCountryCode().isEmpty() &&
                                        entity.getName()!=null &&
                                        !entity.getName().isEmpty() &&
                                        entity.getName().get(0).getName()!=null &&
                                        !entity.getName().get(0).getName().isEmpty() &&
                                        !countryCodes.containsKey(entity.getCountryCode())) {
                                        CountryCode countryCode = new CountryCode()
                                                                        .id(countryCodeMatch.getParticipantID().getValue())
                                                                        .code(entity.getCountryCode())
                                                                        .name(entity.getName().get(0).getName());
                                        countryCodes.put(countryCode.getCode(), countryCode);
                                        LOGGER.info("Found country id={}, code={}, name={}", countryCode.getId(), countryCode.getCode(), countryCode.getName());
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LOGGER.info("Got status=" + status + " when looking up country codes");
                }
            } catch (UnirestException e) {
                LOGGER.info("Got exception when looking up country codes: " + e.getMessage());
            } finally {
                isUpgradingCache.set(false);
            }
        }
        cacheTime = LocalDateTime.now();
    }

    public List<CountryCode> getCountryCodes() {
        update();
        synchronized(countryCodesLock) {
            return new ArrayList(countryCodes.values());
        }
    }

}
