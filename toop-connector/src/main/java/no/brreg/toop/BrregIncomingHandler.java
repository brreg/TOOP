package no.brreg.toop;

import eu.toop.connector.api.me.incoming.*;
import eu.toop.edm.EDMErrorResponse;
import eu.toop.edm.EDMRequest;
import eu.toop.edm.EDMResponse;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;


@Component
public class BrregIncomingHandler implements IMEIncomingHandler {
    @Override
    public void handleIncomingRequest(@Nonnull IncomingEDMRequest incomingEDMRequest) throws MEIncomingException {
        EDMRequest edmRequest = incomingEDMRequest.getRequest();
    }

    @Override
    public void handleIncomingResponse(@Nonnull IncomingEDMResponse incomingEDMResponse) throws MEIncomingException {
        EDMResponse edmResponse = incomingEDMResponse.getResponse();
    }

    @Override
    public void handleIncomingErrorResponse(@Nonnull IncomingEDMErrorResponse incomingEDMErrorResponse) throws MEIncomingException {
        EDMErrorResponse edmErrorResponse = incomingEDMErrorResponse.getErrorResponse();
    }
}
