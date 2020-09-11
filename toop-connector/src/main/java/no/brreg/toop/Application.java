package no.brreg.toop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.helger.commons.system.SystemProperties;
import com.helger.phase4.mgr.MetaAS4Manager;
import com.helger.web.scope.mgr.WebScopeManager;
import com.mashape.unirest.http.ObjectMapper;
import com.mashape.unirest.http.Unirest;
import eu.toop.connector.app.TCInit;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import javax.annotation.PreDestroy;
import javax.servlet.ServletContext;
import java.io.IOException;


@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(
                title = "BRREG TOOP",
                version = no.brreg.toop.generated.spring.ApplicationInfo.VERSION
        )
)
public class Application {
    private static Logger LOGGER = LoggerFactory.getLogger(Application.class);

    @Autowired
    private CountryCodeCache countryCodeCache;

    @Autowired
    private BrregIncomingHandler brregIncomingHandler;

    @Autowired
    private ServletContext servletContext;


    @EventListener(ApplicationReadyEvent.class)
    public void initializeAplication() {
        initializeToopConnector();
        countryCodeCache.update();
    }
    private void initializeToopConnector() {
        SystemProperties.setPropertyValue(MetaAS4Manager.SYSTEM_PROPERTY_PHASE4_MANAGER_INMEMORY, true);
        WebScopeManager.onGlobalBegin(servletContext);
        TCInit.initGlobally(servletContext, brregIncomingHandler);
    }

    @PreDestroy
    public void shutdownToopConnector() {
        TCInit.shutdownGlobally(servletContext);
        WebScopeManager.onGlobalEnd();
    }

    public static void initializeUnirestObjectMapper() {
        //Initialize Unirest object mapper singleton
        Unirest.setObjectMapper(new ObjectMapper() {
            private com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

            @Override
            public <T> T readValue(String value, Class<T> valueType) {
                try {
                    return jacksonObjectMapper.readValue(value, valueType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String writeValue(Object value) {
                try {
                    return jacksonObjectMapper.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public static void main(String[] args) {
        initializeUnirestObjectMapper();
        SpringApplication.run(Application.class, args);
    }

}
