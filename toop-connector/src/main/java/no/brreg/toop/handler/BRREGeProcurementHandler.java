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
import eu.toop.edm.EDMErrorResponse;
import eu.toop.edm.EDMRequest;
import eu.toop.edm.EDMResponse;
import eu.toop.edm.error.EDMExceptionPojo;
import eu.toop.edm.error.EEDMExceptionType;
import eu.toop.edm.error.EToopErrorOrigin;
import eu.toop.edm.model.*;
import eu.toop.edm.request.IEDMRequestPayloadDocumentID;
import eu.toop.edm.request.IEDMRequestPayloadProvider;
import eu.toop.edm.response.ResponseDocumentPojo;
import eu.toop.edm.response.ResponseDocumentReferencePojo;
import eu.toop.regrep.ERegRepResponseStatus;
import no.brreg.toop.LoggerHandler;
import no.brreg.toop.generated.model.QueryType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;


public class BRREGeProcurementHandler extends BRREGBaseHandler {

    public static final IDocumentTypeIdentifier REQUEST_DOCUMENT_TYPE = SimpleIdentifierFactory.INSTANCE.createDocumentTypeIdentifier("toop-doctypeid-qns", "PAYMENT_OF_TAXES::0e639e11-be3d-4f0e-9212-e7960b7177ab::UNSTRUCTURED::toop-edm:v2.1");

    private final String EPROCUREMENT_SAMPLE_LEGAL_PERSON_ID = "974760673";
    private final String EPROCUREMENT_SAMPLE_DOCUMENT = "eProcurement/attestprototype-til-skatteetaten-no.pdf";
    private final String EPROCUREMENT_SAMPLE_DOCUMENT_ID = "00000000-0000-0000-0000-000000000001";
    private final LocalDateTime EPROCUREMENT_SAMPLE_DOCUMENT_ISSUED = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
    private final LocalDate EPROCUREMENT_SAMPLE_DOCUMENT_VALID_FROM = LocalDate.of(2021, 1, 1);
    private final LocalDate EPROCUREMENT_SAMPLE_DOCUMENT_VALID_TO = LocalDate.of(2021, 12, 31);
    private final EToopLanguageCode EPROCUREMENT_SAMPLE_DOCUMENT_LANGUAGE = EToopLanguageCode.NOB;
    private final String EVIDENCE_CREATOR_NAME = "Bergen kemnerkontor";


    public BRREGeProcurementHandler(final ToopIncomingHandler toopIncomingHandler) {
        super(QueryType.EPROCUREMENT, toopIncomingHandler);
    }

    static boolean matchesRequestDocumentType(IDocumentTypeIdentifier documentTypeIdentifier) {
        return (documentTypeIdentifier!=null && documentTypeIdentifier.hasSameContent(REQUEST_DOCUMENT_TYPE));
    }

    @Override
    public void handleIncomingRequest(final IncomingEDMRequest incomingEDMRequest) {
        getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.DEBUG, "Got incoming eProcurement request: " + incomingEDMRequest.toString());

        final EDMRequest edmRequest = incomingEDMRequest.getRequest();

        // Check to see if the correct sample ids are requested
        String requestedLegalPersonId = null;
        String requestedDocumentId = null;
        if (edmRequest.getDataSubjectLegalPerson() != null) {
            requestedLegalPersonId = edmRequest.getDataSubjectLegalPerson().getLegalID();
        }

        final IEDMRequestPayloadProvider requestPayloadProvider = edmRequest.getPayloadProvider();
        if (edmRequest.getPayloadProvider()!=null && edmRequest.getPayloadProvider() instanceof IEDMRequestPayloadDocumentID) {
            requestedDocumentId = ((IEDMRequestPayloadDocumentID)requestPayloadProvider).getDocumentID();
        }

        //Prepare response builders
        MEMessage.Builder meMessageBuilder = MEMessage.builder();
        EDMResponse.AbstractBuilder edmResponseBuilder = null;
        EDMResponse.BuilderDocumentReference edmResponseDocumentReferenceBuilder;
        EDMResponse.BuilderDocument edmResponseDocumentBuilder;
        MEPayload attachmentPayload = null;
        boolean isError = false;

        if (edmRequest.getResponseOption()==EToopResponseOptionType.REFERENCE) { //First request in two-step flow. Return document reference
            if (EPROCUREMENT_SAMPLE_LEGAL_PERSON_ID.equalsIgnoreCase(requestedLegalPersonId)) {
                edmResponseBuilder = edmResponseDocumentReferenceBuilder = EDMResponse.builderDocumentReference();
                edmResponseDocumentReferenceBuilder.responseObject(ResponseDocumentReferencePojo.builder()
                        .registryObjectID(EPROCUREMENT_SAMPLE_DOCUMENT_ID)
                        .dataset(DatasetPojo.builder()
                                .id(EPROCUREMENT_SAMPLE_DOCUMENT_ID)
                                .description("Description")
                                .title("Title")
                                .issued(EPROCUREMENT_SAMPLE_DOCUMENT_ISSUED)
                                .lastModified(EPROCUREMENT_SAMPLE_DOCUMENT_ISSUED)
                                .validFrom(EPROCUREMENT_SAMPLE_DOCUMENT_VALID_FROM)
                                .validTo(EPROCUREMENT_SAMPLE_DOCUMENT_VALID_TO)
                                .language(EPROCUREMENT_SAMPLE_DOCUMENT_LANGUAGE)
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
                                .creator(AgentPojo.builder()
                                        .name(EVIDENCE_CREATOR_NAME)
                                        .build())
                                .build())
                        .build());
            } else {
                isError = true;
            }
        } else if (edmRequest.getResponseOption()==EToopResponseOptionType.INLINE) { //Last request. Return document as attachment
            if (EPROCUREMENT_SAMPLE_DOCUMENT_ID.equalsIgnoreCase(requestedDocumentId) ||
                EPROCUREMENT_SAMPLE_LEGAL_PERSON_ID.equalsIgnoreCase(requestedLegalPersonId)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = BRREGeProcurementHandler.class.getClassLoader().getResourceAsStream(EPROCUREMENT_SAMPLE_DOCUMENT)) {
                    final int BUFFER_SIZE = 10 * 1024; //Just a 10KB buffer. Compromise RAM vs speed
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int readBytes;
                    while (is != null && (readBytes = is.read(buffer, 0, BUFFER_SIZE)) != -1) {
                        baos.write(buffer, 0, readBytes);
                    }

                    attachmentPayload = MEPayload.builder()
                                            .mimeType(new MimeType(EMimeContentType.APPLICATION, "pdf"))
                                            .contentID(EPROCUREMENT_SAMPLE_DOCUMENT_ID)
                                            .data(baos.toByteArray())
                                            .build();
                } catch (IOException e) {
                    getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, "Error while reading sample attachment: ", e);
                }

                edmResponseBuilder = edmResponseDocumentBuilder = EDMResponse.builderDocument();
                edmResponseDocumentBuilder.responseObject(ResponseDocumentPojo.builder()
                        .registryObjectID(EPROCUREMENT_SAMPLE_DOCUMENT_ID)
                        .repositoryItemRef(RepositoryItemRefPojo.builder()
                                .link("Link")
                                .title("Title")
                                .build())
                        .dataset(DatasetPojo.builder()
                                .id(EPROCUREMENT_SAMPLE_DOCUMENT_ID)
                                .description("Description")
                                .title("Title")
                                .issued(EPROCUREMENT_SAMPLE_DOCUMENT_ISSUED)
                                .lastModified(EPROCUREMENT_SAMPLE_DOCUMENT_ISSUED)
                                .validFrom(EPROCUREMENT_SAMPLE_DOCUMENT_VALID_FROM)
                                .validTo(EPROCUREMENT_SAMPLE_DOCUMENT_VALID_TO)
                                .language(EPROCUREMENT_SAMPLE_DOCUMENT_LANGUAGE)
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
                                .creator(AgentPojo.builder()
                                        .name(EVIDENCE_CREATOR_NAME)
                                        .build())
                                .build())
                        .build());
            } else {
                isError = true;
            }
        } else {
            sendIncomingRequestFailed("Unexpected ResponseOption. Got: " + edmRequest.getResponseOption().name());
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
        byte[] dataBuf;
        if (isError) {
            EDMErrorResponse.Builder edmErrorResposeBuilder = EDMErrorResponse.builder();

            edmErrorResposeBuilder.requestID(edmRequest.getRequestID())
                    .specificationIdentifier(CToopEDM.SPECIFICATION_IDENTIFIER_TOOP_EDM_V21)
                    .exception(EDMExceptionPojo.builder()
                            .exceptionType(EEDMExceptionType.INVALID_REQUEST)
                            .severityFailure()
                            .errorMessage("Sample data available for LegalPersonId=" + EPROCUREMENT_SAMPLE_LEGAL_PERSON_ID
                                                                     +" DocumentId=" + EPROCUREMENT_SAMPLE_DOCUMENT_ID + " only.")
                            .errorOrigin(EToopErrorOrigin.RESPONSE_CREATION)
                            .timestampNow()
                            .build())
                    .errorProvider(AgentPojo.builder()
                            .id("9999:norway2")
                            .idSchemeID(EToopIdentifierType.EIDAS)
                            .name("Brønnøysund Register Centre")
                            .address(AddressPojo.builder()
                                    .fullAddress("Brønnøysundregistrene, Havnegata 48, 8900 Brønnøysund, Norway")
                                    .streetName("Havnegata 48")
                                    .postalCode("8910 Brønnøysund")
                                    .town("Brønnøysund")
                                    .countryCode("NO")
                                    .build())
                            .build())
                    .responseStatus(ERegRepResponseStatus.FAILURE);

            dataBuf = edmErrorResposeBuilder.build().getWriter().getAsBytes();
        } else {
            edmResponseBuilder.requestID(edmRequest.getRequestID())
                    .dataProvider(norway())
                    .issueDateTimeNow()
                    .specificationIdentifier(CToopEDM.SPECIFICATION_IDENTIFIER_TOOP_EDM_V21)
                    .responseStatus(ERegRepResponseStatus.SUCCESS);

            dataBuf = edmResponseBuilder.build().getWriter().getAsBytes();
        }

        meMessageBuilder.senderID(incomingEDMRequest.getMetadata().getReceiverID())
                        .receiverID(incomingEDMRequest.getMetadata().getSenderID())
                        .docTypeID(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_1)
                        .processID(EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DOCUMENTQUERY)
                        .payload(MEPayload.builder()
                                .mimeTypeRegRep()
                                .randomContentID()
                                .data(dataBuf)
                                .build());

        if (attachmentPayload != null) {
            meMessageBuilder.addPayload(attachmentPayload);
        }

        final MEMessage meMessage = meMessageBuilder.build();

        //Send response
        try {
            for (MEPayload payload : meMessage.getAllPayloads()) {
                getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.INFO, "Sending eProcurement payload: " + new String(payload.getData().bytes(), StandardCharsets.UTF_8));
            }
            TCAPIHelper.sendAS4Message(meRoutingInformation, meMessage);
        } catch (MEOutgoingException e) {
            sendIncomingRequestFailed("Got exception when sending AS4 message: " + e.getMessage());
        }
    }

    @Override
    public void handleIncomingResponse(final IncomingEDMResponse incomingEDMResponse) {
    }

}
