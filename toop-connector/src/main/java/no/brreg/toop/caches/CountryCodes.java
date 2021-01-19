package no.brreg.toop.caches;

// This code is Public Domain. See LICENSE

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import no.brreg.toop.generated.model.*;
import no.brreg.toop.handler.BRREGGBMHandler;
import no.brreg.toop.handler.BRREGeProcurementHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAmount;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


@Component
public class CountryCodes {
    private static final Logger LOGGER = LoggerFactory.getLogger(CountryCodes.class);

    private static final String LOOKUP_URL = "https://directory.acc.exchange.toop.eu/search/1.0/json?doctype=";
    public static final String COUNTRY_SCHEME = "iso6523-actorid-upis";

    private LocalDateTime cacheTime = null;
    private static final TemporalAmount CACHE_VALID_DURATION = Duration.ofHours(12);
    private final AtomicBoolean isUpgradingCache = new AtomicBoolean(false);

    private final Map<String,CountryCode> countryCodes = new HashMap<>();
    private final Object countryCodesLock = new Object();

    private QueryType queryType;


    CountryCodes() {
    }

    CountryCodes(final QueryType queryType) {
        setQueryType(queryType);
    }

    private String lookupUrlForQueryType() {
        try {
            if (queryType == QueryType.GBM) {
                return LOOKUP_URL+ URLEncoder.encode(BRREGGBMHandler.DOCUMENT_TYPE.getScheme()+"::"+BRREGGBMHandler.DOCUMENT_TYPE.getValue(), "utf-8");
            } else if (queryType == QueryType.EPROCUREMENT) {
                return LOOKUP_URL+ URLEncoder.encode(BRREGeProcurementHandler.DOCUMENT_TYPE.getScheme()+"::"+BRREGeProcurementHandler.DOCUMENT_TYPE.getValue(), "utf-8");
            }
        } catch(Exception e) {
            LOGGER.error("Got error:", e);
        }
        return null;
    }

    public void setQueryType(final QueryType queryType) {
        this.queryType = queryType;
    }

    void update() {
        if (cacheTime!=null && cacheTime.plus(CACHE_VALID_DURATION).isAfter(LocalDateTime.now())) {
            return; //Cache is still valid
        }

        LOGGER.info("Updating CountryCode cache for " + queryType.name());
        if (isUpgradingCache.compareAndSet(false, true)) {
            try {
                HttpResponse<CountryCodeResult> countryLookupResponse = Unirest.get(lookupUrlForQueryType())
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

    List<CountryCode> getCountryCodes() {
        update();
        synchronized(countryCodesLock) {
            return new ArrayList<>(countryCodes.values());
        }
    }

    CountryCode getCountryCode(final String country) {
        synchronized(countryCodesLock) {
            return countryCodes.get(country);
        }
    }

}
