package no.brreg.toop.handler;

// This code is Public Domain. See LICENSE

import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.factory.SimpleIdentifierFactory;
import eu.toop.connector.api.me.incoming.IncomingEDMRequest;
import eu.toop.connector.api.me.incoming.IncomingEDMResponse;
import no.brreg.toop.generated.model.QueryType;


public class BRREGeProcurementHandler extends BRREGBaseHandler {

    public static final IDocumentTypeIdentifier DOCUMENT_TYPE = SimpleIdentifierFactory.INSTANCE.createDocumentTypeIdentifier("toop-doctypeid-qns", "PAYMENT_OF_TAXES::0e639e11-be3d-4f0e-9212-e7960b7177ab::UNSTRUCTURED::toop-edm:v2.1");


    public BRREGeProcurementHandler(final ToopIncomingHandler toopIncomingHandler) {
        super(QueryType.EPROCUREMENT, toopIncomingHandler);
    }

    static boolean matchesDocumentType(IDocumentTypeIdentifier documentTypeIdentifier) {
        return (documentTypeIdentifier!=null && documentTypeIdentifier.hasSameContent(DOCUMENT_TYPE));
    }

    @Override
    public void handleIncomingRequest(final IncomingEDMRequest incomingEDMRequest) {
    }

    @Override
    public void handleIncomingResponse(final IncomingEDMResponse incomingEDMResponse) {
    }

}
