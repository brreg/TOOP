package no.brreg.toop.caches;

// This code is Public Domain. See LICENSE

import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import no.brreg.toop.generated.model.*;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(CountryCodeCache.class);

    private static final String BUSINESSCARDS_LOOKUP = "https://directory.acc.exchange.toop.eu/export/businesscards";
    public static final String COUNTRY_SCHEME = "iso6523-actorid-upis";

    private LocalDateTime cacheTime = null;
    private static final TemporalAmount CACHE_VALID_DURATION = Duration.ofHours(12);
    private final AtomicBoolean isUpgradingCache = new AtomicBoolean(false);

    private static final Map<String,CountryCode> countryCodes = new HashMap<>();
    private static final Object countryCodesLock = new Object();


    public void update(final boolean force) {
        LOGGER.info("Updating CountryCode cache");
        if (!force && cacheTime!=null && cacheTime.plus(CACHE_VALID_DURATION).isAfter(LocalDateTime.now())) {
            return; //Cache is still valid
        }

        if (isUpgradingCache.compareAndSet(false, true)) {
            try {
                HttpResponse<String> businesscardResponse = Unirest.get(BUSINESSCARDS_LOOKUP)
                        .header("accept", "application/xml")
                        .asString();

                int status = businesscardResponse.getStatus();
                if (status >= 200 && status <= 299) {
                    JacksonXmlModule module = new JacksonXmlModule();
                    module.setDefaultUseWrapper(false);

                    XmlMapper mapper = new XmlMapper(module);

                    BusinessCardResultRoot businesscards = mapper.readValue(businesscardResponse.getBody(), BusinessCardResultRoot.class);

                    synchronized(countryCodesLock) {
                        countryCodes.clear();
                        for (BusinessCardResultBusinessCard businessCard : businesscards.getBusinesscards()) {
                            if (businessCard!=null &&
                                businessCard.getParticipant()!=null &&
                                COUNTRY_SCHEME.equals(businessCard.getParticipant().getScheme()) &&
                                businessCard.getParticipant().getValue()!=null &&
                                !businessCard.getParticipant().getValue().isEmpty() &&
                                businessCard.getEntities()!=null &&
                                !businessCard.getEntities().isEmpty()) {
                                for (BusinessCardResultEntity entity : businessCard.getEntities()) {
                                    if (entity!=null &&
                                        entity.getCountrycode()!=null &&
                                        !entity.getCountrycode().isEmpty() &&
                                        entity.getNames()!=null &&
                                        !entity.getNames().isEmpty() &&
                                        entity.getNames().get(0).getName()!=null &&
                                        !entity.getNames().get(0).getName().isEmpty() &&
                                        !countryCodes.containsKey(entity.getCountrycode())) {
                                        CountryCode countryCode = new CountryCode()
                                                                        .id(businessCard.getParticipant().getValue())
                                                                        .code(entity.getCountrycode())
                                                                        .name(entity.getNames().get(0).getName());

                                        List<CountryCodeDocType> docTypes = new ArrayList<>();
                                        if (businessCard.getDoctypeids() != null) {
                                            for (BusinessCardResultDoctypeid docType : businessCard.getDoctypeids()) {
                                                docTypes.add(new CountryCodeDocType()
                                                        .scheme(docType.getScheme())
                                                        .value(docType.getValue()));
                                            }
                                        }
                                        countryCode.docTypes(docTypes);

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
            } catch (Exception e) {
                LOGGER.info("Got exception when looking up country codes: " + e.getMessage());
            } finally {
                isUpgradingCache.set(false);
            }
        }
        cacheTime = LocalDateTime.now();
    }

    public List<CountryCode> getCountryCodes() {
        update(false);
        synchronized(countryCodesLock) {
            return new ArrayList<>(countryCodes.values());
        }
    }

    public CountryCode getCountryCode(final String country) {
        synchronized(countryCodesLock) {
            return countryCodes.get(country);
        }
    }

}
