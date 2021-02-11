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

    private static final List<CountryCode> countryCodes = new ArrayList<>();
    private static final Object countryCodesLock = new Object();


    public void update(final boolean force) {
        if (!force && cacheTime!=null && cacheTime.plus(CACHE_VALID_DURATION).isAfter(LocalDateTime.now())) {
            return; //Cache is still valid
        }

        if (isUpgradingCache.compareAndSet(false, true)) {
            try {
                LOGGER.info("Updating CountryCode cache");

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
                                        !entity.getNames().get(0).getName().isEmpty()) {
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

                                        countryCodes.add(countryCode);
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
            return countryCodes;
        }
    }

    public CountryCode getCountryCodeById(final String id) {
        update(false);
        synchronized(countryCodesLock) {
            for (CountryCode countryCode : countryCodes) {
                if (countryCode.getId().equalsIgnoreCase(id)) {
                    return countryCode;
                }
            }
            return null;
        }
    }

    public List<CountryCode> getCountryCode(final String country, final CountryCodeDocType documentTypeId) {
        update(false);
        synchronized(countryCodesLock) {
            List<CountryCode> result = new ArrayList<>();
            for (CountryCode countryCode : countryCodes) {
                if (countryCode.getCode().equalsIgnoreCase(country)) {
                    for (CountryCodeDocType docType : countryCode.getDocTypes()) {
                        if (docType.equals(documentTypeId)) {
                            result.add(countryCode);
                        }
                    }
                }
            }
            return result;
        }
    }

    public List<CountryCodeDocType> getDocumentTypes(final String country) {
        update(false);
        synchronized(countryCodesLock) {
            List<CountryCodeDocType> result = new ArrayList<>();
            for (CountryCode countryCode : countryCodes) {
                if (countryCode.getCode().equalsIgnoreCase(country)) {
                    for (CountryCodeDocType docType : countryCode.getDocTypes()) {
                        if (!result.contains(docType)) {
                            result.add(docType);
                        }
                    }
                }
            }
            return result;
        }
    }

}
