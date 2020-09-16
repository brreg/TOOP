package no.brreg.toop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


@Component
public class CountryCodeCache {
    private static Logger LOGGER = LoggerFactory.getLogger(CountryCodeCache.class);

    private LocalDateTime cacheTime = null;
    private static final TemporalAmount CACHE_VALID_DURATION = Duration.ofHours(12);
    private AtomicBoolean isUpgradingCache = new AtomicBoolean(false);

    private static List<String> countryCodes = new ArrayList<>();
    private static Object countryCodesLock = new Object();


    public void update() {
        LOGGER.info("Updating CountryCode cache");
        if (cacheTime!=null && cacheTime.plus(CACHE_VALID_DURATION).isAfter(LocalDateTime.now())) {
            return; //Cache is still valid
        }

        if (isUpgradingCache.compareAndSet(false, true)) {
            try {
                //Upgrade cache
            } finally {
                isUpgradingCache.set(false);
            }
        }
        cacheTime = LocalDateTime.now();
    }

    public List<String> getCountryCodes() {
        update();
        synchronized(countryCodesLock) {
            return countryCodes;
        }
    }

}
