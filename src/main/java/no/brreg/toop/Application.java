package no.brreg.toop;

import com.helger.commons.system.SystemProperties;
import com.helger.phase4.mgr.MetaAS4Manager;
import com.helger.web.scope.mgr.WebScopeManager;
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
    private ServletContext servletContext;


    @EventListener(ApplicationReadyEvent.class)
    public void initializeAplication() {
        initializeToopConnector();
        countryCodeCache.update();
    }
    private void initializeToopConnector() {
        SystemProperties.setPropertyValue(MetaAS4Manager.SYSTEM_PROPERTY_PHASE4_MANAGER_INMEMORY, true);
        WebScopeManager.onGlobalBegin(servletContext);
        TCInit.initGlobally(servletContext, null);
    }

    @PreDestroy
    public void shutdownToopConnector() {
        TCInit.shutdownGlobally(servletContext);
        WebScopeManager.onGlobalEnd();
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
