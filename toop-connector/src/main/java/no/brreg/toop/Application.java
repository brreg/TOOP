package no.brreg.toop;

// This code is Public Domain. See LICENSE

import com.fasterxml.jackson.core.JsonProcessingException;
import com.helger.web.scope.mgr.WebScopeManager;
import eu.toop.connector.app.TCInit;
import eu.toop.connector.mem.phase4.servlet.AS4ReceiveServlet;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import kong.unirest.ObjectMapper;
import kong.unirest.Unirest;
import no.brreg.toop.handler.ToopIncomingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import javax.annotation.PreDestroy;
import javax.servlet.ServletContext;
import java.io.IOException;


@SpringBootApplication
@ComponentScan(basePackages = {"no.brreg.toop", "eu.toop.connector.mem.phase4.servlet"})
@OpenAPIDefinition(
        info = @Info(
                title = "BRREG TOOP",
                version = no.brreg.toop.generated.spring.ApplicationInfo.VERSION
        )
)
public class Application {
    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    @Autowired
    private CountryCodeCache countryCodeCache;

    @Autowired
    private ToopIncomingHandler toopIncomingHandler;

    @Autowired
    private ServletContext servletContext;

    @Bean
    public ServletRegistrationBean as4Bean() {
        initializeApplication();
        LOGGER.info("registering phase4 bean");
        ServletRegistrationBean bean = new ServletRegistrationBean(new AS4ReceiveServlet(), "/phase4");
        bean.setLoadOnStartup(1);
        return bean;
    }

    public void initializeApplication() {
        initializeToopConnector();
        countryCodeCache.update();
    }
    private void initializeToopConnector() {
        LOGGER.info("Initializing toop connector");
        WebScopeManager.onGlobalBegin(servletContext);
        TCInit.initGlobally(servletContext, toopIncomingHandler);
    }

    @PreDestroy
    public void shutdownApplication() {
        TCInit.shutdownGlobally(servletContext);
        WebScopeManager.onGlobalEnd();
        Unirest.shutDown();
    }

    public static void initializeUnirestObjectMapper() {
        Unirest.config().setObjectMapper(new ObjectMapper() {
            private final com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

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
