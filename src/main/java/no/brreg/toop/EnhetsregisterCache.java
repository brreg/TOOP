package no.brreg.toop;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import no.brreg.toop.generated.model.Enhet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;


@Component
public class EnhetsregisterCache {
    private static Logger LOGGER = LoggerFactory.getLogger(EnhetsregisterCache.class);


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

        String url = null;
        try {
            url = enhetsregisterApiUrl + URLEncoder.encode(orgno, "utf-8");
            LOGGER.info("GET "+url);
            HttpResponse<Enhet> enhetResponse = Unirest.get(url)
                    .header("accept", "application/json")
                    .asObject(Enhet.class);
            int status = enhetResponse.getStatus();
            if (status >= 200 && status <= 299) {
                Enhet enhet = enhetResponse.getBody();
                LOGGER.info("Got name=\""+enhet.getNavn()+"\" from enhetsregisteret for orgno "+orgno);
                return enhet;
            } else {
                LOGGER.info("Got status="+status+" when HTTP GET "+url);
                return null;
            }
        } catch (UnsupportedEncodingException|UnirestException e) {
            LOGGER.info("Got exception when HTTP GET "+url+", :"+e.getMessage());
            return null;
        }
    }

}
