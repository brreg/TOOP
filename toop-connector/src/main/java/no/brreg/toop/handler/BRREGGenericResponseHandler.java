package no.brreg.toop.handler;

// This code is Public Domain. See LICENSE

import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.factory.SimpleIdentifierFactory;
import eu.toop.connector.api.me.incoming.IncomingEDMRequest;
import eu.toop.connector.api.me.incoming.IncomingEDMResponse;


public class BRREGGenericResponseHandler extends BRREGBaseHandler {

    public static final IDocumentTypeIdentifier RESPONSE_DOCUMENT_TYPE = SimpleIdentifierFactory.INSTANCE.createDocumentTypeIdentifier("toop-doctypeid-qns", "QueryResponse::toop-edm:v2.1");


    public BRREGGenericResponseHandler(final ToopIncomingHandler toopIncomingHandler) {
        super(null, toopIncomingHandler);
    }

    static boolean matchesResponseDocumentType(IDocumentTypeIdentifier documentTypeIdentifier) {
        return (documentTypeIdentifier!=null && documentTypeIdentifier.hasSameContent(RESPONSE_DOCUMENT_TYPE));
    }

    @Override
    public void handleIncomingRequest(final IncomingEDMRequest incomingEDMRequest) {
    }

    @Override
    public void handleIncomingResponse(final IncomingEDMResponse incomingEDMResponse) {
        ToopIncomingHandler.ToopRequest pendingRequest = getToopIncomingHandler().getPendingRequest(incomingEDMResponse.getResponse().getRequestID());
        if (pendingRequest != null) {
            pendingRequest.getHandler().handleIncomingResponse(incomingEDMResponse);
        }
    }

}
