package no.brreg.toop;

// This code is Public Domain. See LICENSE

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import no.brreg.toop.generated.model.Enhet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.util.HashMap;


@Component
public class EnhetsregisterCache {
    private static Logger LOGGER = LoggerFactory.getLogger(EnhetsregisterCache.class);

    private class EnhetItem {
        private Enhet enhet;
        public LocalDateTime lastAccessed;
        public EnhetItem(final Enhet enhet) {
            this.enhet = enhet;
            this.lastAccessed = LocalDateTime.now();
        }
        public Enhet getEnhet() {
            this.lastAccessed = LocalDateTime.now();
            return enhet;
        }
    }

    private static final int MAX_CACHE_ENTRIES = 1000;
    private static HashMap<String,EnhetItem> enheter = new HashMap<>();
    private static Object enheterLock = new Object();


    private int getMaxCacheEntries() {
        return MAX_CACHE_ENTRIES;
    }

    public Enhet getEnhet(final String orgno) {
        Enhet enhet = getEnhetFromEnhetsregisteret(orgno, "https://data.brreg.no/enhetsregisteret/api/enheter/");
        if (enhet == null)
        {
            enhet = getEnhetFromEnhetsregisteret(orgno, "https://data.brreg.no/enhetsregisteret/api/underenheter/");
        }
        return enhet;
    }

    private Enhet getEnhetFromEnhetsregisteret(final String orgno, final String enhetsregisterApiUrl) {
        if (orgno==null || orgno.isEmpty()) {
            return null;
        }

        synchronized(EnhetsregisterCache.enheterLock) {
            if (EnhetsregisterCache.enheter.containsKey(orgno)) {
                return EnhetsregisterCache.enheter.get(orgno).getEnhet();
            }

            String url = null;
            try {
                url = enhetsregisterApiUrl + URLEncoder.encode(orgno, "utf-8");
                HttpResponse<Enhet> enhetResponse = Unirest.get(url)
                        .header("accept", "application/json")
                        .asObject(Enhet.class);
                int status = enhetResponse.getStatus();
                if (status >= 200 && status <= 299) {
                    Enhet enhet = enhetResponse.getBody();
                    return cacheEnhet(enhet);
                } else {
                    LOGGER.info("Got status=" + status + " when HTTP GET " + url);
                    return null;
                }
            } catch (UnsupportedEncodingException | UnirestException e) {
                LOGGER.info("Got exception when HTTP GET " + url + ", :" + e.getMessage());
                return null;
            }
        }
    }

    private Enhet cacheEnhet(final Enhet enhet) {
        if (enhet == null) {
            return null;
        }

        synchronized(EnhetsregisterCache.enheterLock) {
            //Add item to cache
            EnhetsregisterCache.enheter.put(enhet.getOrganisasjonsnummer(), new EnhetItem(enhet));

            //Purge oldest items from cache
            while (EnhetsregisterCache.enheter.size() > getMaxCacheEntries()) {
                EnhetItem oldestItem = null;
                for (EnhetItem item : EnhetsregisterCache.enheter.values()) {
                    if (oldestItem==null || item.lastAccessed.isBefore(oldestItem.lastAccessed)) {
                        oldestItem = item;
                    }
                }
                EnhetsregisterCache.enheter.remove(oldestItem.getEnhet().getOrganisasjonsnummer());
            }
        }
        return enhet;
    }

}
