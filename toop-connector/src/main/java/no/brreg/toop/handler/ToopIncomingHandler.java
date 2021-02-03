package no.brreg.toop.handler;

// This code is Public Domain. See LICENSE

import com.helger.peppolid.IDocumentTypeIdentifier;
import eu.toop.connector.api.me.incoming.*;
import eu.toop.edm.EDMErrorResponse;
import no.brreg.toop.caches.CountryCodeCache;
import no.brreg.toop.caches.EnhetsregisterCache;
import no.brreg.toop.LoggerHandler;
import no.brreg.toop.generated.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.*;


@Component
public class ToopIncomingHandler implements IMEIncomingHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToopIncomingHandler.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(28);


    @Autowired
    private EnhetsregisterCache enhetsregisterCache;

    @Autowired
    private CountryCodeCache countryCodeCache;

    @Autowired
    private LoggerHandler loggerHandler;

    public static class ToopResponse {
        private Enhet enhet;
        private HttpStatus status;
        private String errorMessage;

        public ToopResponse() {
            this.enhet = null;
            this.status = HttpStatus.GATEWAY_TIMEOUT;
            this.errorMessage = null;
        }

        public ToopResponse(final HttpStatus status, final String errorMessage) {
            this.enhet = null;
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public Enhet getEnhet() {
            return enhet;
        }

        public void setEnhet(final Enhet enhet) {
            this.enhet = enhet;
        }

        public HttpStatus getStatus() {
            return status;
        }

        public void setStatus(final HttpStatus status) {
            this.status = status;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(final String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    class ToopRequest {
        private final BRREGBaseHandler handler;
        private final String id;
        private final Object lock = new Object();
        private final ToopResponse response = new ToopResponse();

        public ToopRequest(final BRREGBaseHandler handler, final String id) {
            this.handler = handler;
            this.id = id;
        }

        public BRREGBaseHandler getHandler() {
            return handler;
        }

        public String getId() {
            return id;
        }

        public Object getLock() {
            return lock;
        }

        public ToopResponse getResponse() {
            return response;
        }
    }
    private final Map<String, ToopRequest> requestMap = new HashMap<>();
    private static final Object requestMapLock = new Object();


    public final EnhetsregisterCache getEnhetsregisterCache() {
        return enhetsregisterCache;
    }

    public final CountryCodeCache getCountryCodeCache() {
        return countryCodeCache;
    }

    public final LoggerHandler getLoggerHandler() {
        return loggerHandler;
    }


    @Override
    public void handleIncomingRequest(@Nonnull IncomingEDMRequest incomingEDMRequest) throws MEIncomingException {
        IDocumentTypeIdentifier requestedDocumentType = incomingEDMRequest.getMetadata().getDocumentTypeID();
        if (BRREGGBMHandler.matchesRequestDocumentType(requestedDocumentType)) {
            new BRREGGBMHandler(this).handleIncomingRequest(incomingEDMRequest);
        } else if (BRREGeProcurementHandler.matchesRequestDocumentType(requestedDocumentType)) {
            new BRREGeProcurementHandler(this).handleIncomingRequest(incomingEDMRequest);
        } else {
            LOGGER.info("Unexpected incoming request document type: {}", requestedDocumentType.getScheme()+"#"+requestedDocumentType.getValue());
        }
    }

    @Override
    public void handleIncomingResponse(@Nonnull IncomingEDMResponse incomingEDMResponse) throws MEIncomingException {
        IDocumentTypeIdentifier responseDocumentType = incomingEDMResponse.getMetadata().getDocumentTypeID();
        if (BRREGGenericResponseHandler.matchesResponseDocumentType(responseDocumentType)) {
            new BRREGGenericResponseHandler(this).handleIncomingResponse(incomingEDMResponse);
        } else {
            LOGGER.info("Unexpected incoming response document type: {}", responseDocumentType.getScheme()+"#"+responseDocumentType.getValue());
        }
    }

    @Override
    public void handleIncomingErrorResponse(@Nonnull IncomingEDMErrorResponse incomingEDMErrorResponse) throws MEIncomingException {
        EDMErrorResponse edmErrorResponse = incomingEDMErrorResponse.getErrorResponse();
        loggerHandler.log(LoggerHandler.Level.INFO, "Got incoming error reponse for request " + edmErrorResponse.getRequestID());

        removePendingRequest(edmErrorResponse.getRequestID(), null, HttpStatus.NOT_FOUND);
    }

    public ToopIncomingHandler.ToopResponse getByIdentifier(final String countrycode, final String identifier, final Map<String,Object> properties, final boolean isLegalPerson) {
        return new BRREGGBMHandler(this).getByIdentifier(countrycode, identifier, properties, isLegalPerson);
    }


    public ToopRequest getPendingRequest(final String requestId) {
        synchronized (requestMapLock) {
            return requestMap.get(requestId);
        }
    }

    public ToopResponse addPendingRequest(final BRREGBaseHandler handler, final String requestId) {
        ToopRequest request = new ToopRequest(handler, requestId);
        try {
            synchronized(requestMapLock) {
                requestMap.put(request.getId(), request);
            }
            synchronized(request.getLock()) {
                request.getLock().wait(REQUEST_TIMEOUT.toMillis());
            }
            return request.getResponse();
        } catch (InterruptedException e) {
            final String msg = "Request timed out";
            loggerHandler.log(LoggerHandler.Level.ERROR, msg);
            return new ToopIncomingHandler.ToopResponse(HttpStatus.GATEWAY_TIMEOUT, msg);
        } finally {
            synchronized(requestMapLock) {
                requestMap.remove(request.getId());
            }
        }
    }

    public void removePendingRequest(final String requestId, final Enhet responseEnhet, final HttpStatus responseStatus) {
        synchronized (requestMapLock) {
            ToopRequest request = requestMap.get(requestId);
            if (request == null) {
                loggerHandler.log(LoggerHandler.Level.INFO, "Request "+requestId+" already removed from pending queue");
                return;
            }

            synchronized(request) {
                request.getResponse().setEnhet(responseEnhet);
                request.getResponse().setStatus(responseStatus);
                requestMap.remove(request);
                synchronized(request.getLock()) {
                    request.getLock().notify();
                }
            }
        }
    }

}
