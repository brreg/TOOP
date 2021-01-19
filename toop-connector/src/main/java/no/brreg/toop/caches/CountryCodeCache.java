package no.brreg.toop.caches;

// This code is Public Domain. See LICENSE

import no.brreg.toop.generated.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;


@Component
public class CountryCodeCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(CountryCodeCache.class);

    private static final Map<QueryType,CountryCodes> countryCodesMap = new HashMap<>();
    private static final Object countryCodesLock = new Object();


    private final CountryCodes getCountryCodesMap(final QueryType type) {
        synchronized (countryCodesLock) {
            if (!countryCodesMap.containsKey(type)) {
                countryCodesMap.put(type, new CountryCodes(type));
            }
            return countryCodesMap.get(type);
        }
    }

    public void update(final QueryType type) {
        getCountryCodesMap(type).update();
    }

    public List<CountryCode> getCountryCodes(final QueryType type) {
        return getCountryCodesMap(type).getCountryCodes();
    }

    public CountryCode getCountryCode(final QueryType type, final String country) {
        return getCountryCodesMap(type).getCountryCode(country);
    }

}
