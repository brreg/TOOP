package no.brreg.toop.handler;

// This code is Public Domain. See LICENSE

import com.helger.commons.mime.EMimeContentType;
import com.helger.commons.mime.MimeType;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.factory.SimpleIdentifierFactory;
import eu.toop.commons.codelist.EPredefinedDocumentTypeIdentifier;
import eu.toop.commons.codelist.EPredefinedProcessIdentifier;
import eu.toop.connector.api.me.incoming.IncomingEDMRequest;
import eu.toop.connector.api.me.incoming.IncomingEDMResponse;
import eu.toop.connector.api.me.model.MEMessage;
import eu.toop.connector.api.me.model.MEPayload;
import eu.toop.connector.api.me.outgoing.MEOutgoingException;
import eu.toop.connector.api.me.outgoing.MERoutingInformation;
import eu.toop.connector.app.api.TCAPIHelper;
import eu.toop.edm.CToopEDM;
import eu.toop.edm.EDMRequest;
import eu.toop.edm.EDMResponse;
import eu.toop.edm.model.*;
import eu.toop.edm.request.IEDMRequestPayloadDistribution;
import eu.toop.edm.response.ResponseDocumentPojo;
import eu.toop.edm.response.ResponseDocumentReferencePojo;
import eu.toop.regrep.ERegRepResponseStatus;
import no.brreg.toop.LoggerHandler;
import no.brreg.toop.generated.model.QueryType;

import java.io.*;
import java.util.List;


public class BRREGeProcurementHandler extends BRREGBaseHandler {

    public static final IDocumentTypeIdentifier REQUEST_DOCUMENT_TYPE = SimpleIdentifierFactory.INSTANCE.createDocumentTypeIdentifier("toop-doctypeid-qns", "PAYMENT_OF_TAXES::0e639e11-be3d-4f0e-9212-e7960b7177ab::UNSTRUCTURED::toop-edm:v2.1");

    private final String EPROCUREMENT_SAMPLE_DOCUMENT = "eProcurement/attestprototype-til-skatteetaten-no.pdf";


    public BRREGeProcurementHandler(final ToopIncomingHandler toopIncomingHandler) {
        super(QueryType.EPROCUREMENT, toopIncomingHandler);
    }

    static boolean matchesRequestDocumentType(IDocumentTypeIdentifier documentTypeIdentifier) {
        return (documentTypeIdentifier!=null && documentTypeIdentifier.hasSameContent(REQUEST_DOCUMENT_TYPE));
    }

    @Override
    public void handleIncomingRequest(final IncomingEDMRequest incomingEDMRequest) {
        getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.INFO, "Got incoming eProcurement request: " + incomingEDMRequest.toString());

        final EDMRequest edmRequest = incomingEDMRequest.getRequest();

        //Is this a request we support?
        if (!(edmRequest.getPayloadProvider() instanceof IEDMRequestPayloadDistribution)) {
            sendIncomingRequestFailed("Cannot create TOOP response for eProcurement DocumentRequest: " + edmRequest.getPayloadProvider().getClass().getSimpleName());
            return;
        }

        //Is the request in a structure we support?
        final IEDMRequestPayloadDistribution requestDistribution = (IEDMRequestPayloadDistribution) edmRequest.getPayloadProvider();
        final List<DistributionPojo> distributions = requestDistribution.distributions();
        if (distributions.size() != 1) {
            sendIncomingRequestFailed("Expected exactly one top-level request distribution. Got:  " + distributions.size());
            return;
        }

        //Is this a request for application/pdf?
        final DistributionPojo distributionRequest = distributions.get(0);
        if (!"application/pdf".equalsIgnoreCase(distributionRequest.getMediaType())) {
            sendIncomingRequestFailed("Expected top-level request distribution media type \"application/pdf\". Got: " + distributionRequest.getMediaType());
            return;
        }

        MEMessage.Builder meMessageBuilder = MEMessage.builder();
        EDMResponse.AbstractBuilder edmResponseBuilder;
        EDMResponse.BuilderDocumentReference edmResponseDocumentReferenceBuilder;
        EDMResponse.BuilderDocument edmResponseDocumentBuilder;

        EToopResponseOptionType responseOptionType = edmRequest.getResponseOption();
        if (responseOptionType == EToopResponseOptionType.REFERENCE) { //First request. Return document reference
            edmResponseBuilder = edmResponseDocumentReferenceBuilder = EDMResponse.builderDocumentReference();
            edmResponseDocumentReferenceBuilder.responseObject(ResponseDocumentReferencePojo.builder()
                    .randomRegistryObjectID()
                    .dataset(DatasetPojo.builder()
                            .description("Description")
                            .title("Title")
                            .distribution(DocumentReferencePojo.builder()
                                    .documentDescription("DocumentDescription")
                                    .documentURI("DocumentURI")
                                    .documentType("application/pdf")
                                    .build())
                            .qualifiedRelation(QualifiedRelationPojo.builder()
                                    .description("Description")
                                    .title("Title")
                                    .id("Id")
                                    .build())
                            .build())
                    .build());
        } else if (responseOptionType == EToopResponseOptionType.INLINE) { //Second request. Return document as attachment
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = BRREGeProcurementHandler.class.getClassLoader().getResourceAsStream(EPROCUREMENT_SAMPLE_DOCUMENT)) {
                final int BUFFER_SIZE = 10 * 1024; //Just a 10KB buffer. Compromise RAM vs speed
                byte[] buffer = new byte[BUFFER_SIZE];
                int readBytes;
                while (is!=null && (readBytes = is.read(buffer, 0, BUFFER_SIZE)) != -1) {
                    baos.write(buffer, 0, readBytes);
                }

                meMessageBuilder.payload(MEPayload.builder()
                        .mimeType(new MimeType(EMimeContentType.APPLICATION, "pdf"))
                        .randomContentID()
                        .data(baos.toByteArray())
                        .build());
            } catch (IOException e) {
                getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, "Error while reading sample attachment: ", e);
            }

            edmResponseBuilder = edmResponseDocumentBuilder = EDMResponse.builderDocument();
            edmResponseDocumentBuilder.responseObject(ResponseDocumentPojo.builder()
                    .randomRegistryObjectID()
                    .repositoryItemRef(RepositoryItemRefPojo.builder()
                            .link("Link")
                            .title("Title")
                            .build())
                    .dataset(DatasetPojo.builder()
                            .description("Description")
                            .title("Title")
                            .distribution(DocumentReferencePojo.builder()
                                    .documentDescription("DocumentDescription")
                                    .documentURI("DocumentURI")
                                    .documentType("application/pdf")
                                    .build())
                            .qualifiedRelation(QualifiedRelationPojo.builder()
                                    .description("Description")
                                    .title("Title")
                                    .id("Id")
                                    .build())
                            .build())
                    .build());
        } else {
            sendIncomingRequestFailed("Expected response option type INLINE or REFERENCE. Got: " + responseOptionType.name());
            return;
        }

        final MERoutingInformation meRoutingInformation = getRoutingInformation(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_1,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DOCUMENTQUERY,
                incomingEDMRequest.getMetadata().getReceiverID() /* incoming receiver is now sender */,
                incomingEDMRequest.getMetadata().getSenderID() /* incoming sender is now receiver */);
        if (meRoutingInformation == null) {
            sendIncomingRequestFailed("Failed to get RoutingInformation");
            return;
        }

        //Create message
        edmResponseBuilder.requestID(edmRequest.getRequestID())
                .dataProvider(norway())
                .issueDateTimeNow()
                .specificationIdentifier(CToopEDM.SPECIFICATION_IDENTIFIER_TOOP_EDM_V21)
                .responseStatus(ERegRepResponseStatus.SUCCESS);

        byte[] dataBuf = edmResponseBuilder.build().getWriter().getAsBytes();

        final MEMessage meMessage = meMessageBuilder
                .senderID(incomingEDMRequest.getMetadata().getReceiverID())
                .receiverID(incomingEDMRequest.getMetadata().getSenderID())
                .docTypeID(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_1)
                .processID(EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DOCUMENTQUERY)
                .payload(MEPayload.builder()
                        .mimeTypeRegRep()
                        .randomContentID()
                        .data(dataBuf)
                        .build())
                .build();

        //Send response
        try {
            //meMessage.toString();
            //getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, "Error while reading sample attachment: ", e);
            TCAPIHelper.sendAS4Message(meRoutingInformation, meMessage);
        } catch (MEOutgoingException e) {
            sendIncomingRequestFailed("Got exception when sending AS4 message: " + e.getMessage());
        }
    }

    @Override
    public void handleIncomingResponse(final IncomingEDMResponse incomingEDMResponse) {
    }

}
