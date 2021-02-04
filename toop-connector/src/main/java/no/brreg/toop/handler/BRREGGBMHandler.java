package no.brreg.toop.handler;

import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
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
import eu.toop.edm.error.EToopDataElementResponseErrorCode;
import eu.toop.edm.error.EToopErrorOrigin;
import eu.toop.edm.model.*;
import eu.toop.edm.pilot.gbm.EToopConcept;
import eu.toop.edm.request.IEDMRequestPayloadConcepts;
import eu.toop.edm.response.IEDMResponsePayloadConcepts;
import eu.toop.edm.response.IEDMResponsePayloadProvider;
import eu.toop.regrep.ERegRepResponseStatus;
import no.brreg.toop.LoggerHandler;
import no.brreg.toop.caches.CountryCodeCache;
import no.brreg.toop.generated.model.*;
import org.springframework.http.HttpStatus;

// This code is Public Domain. See LICENSE

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class BRREGGBMHandler extends BRREGBaseHandler {

    public static final IDocumentTypeIdentifier REQUEST_DOCUMENT_TYPE = SimpleIdentifierFactory.INSTANCE.createDocumentTypeIdentifier("toop-doctypeid-qns", "RegisteredOrganization::REGISTERED_ORGANIZATION_TYPE::CONCEPT##CCCEV::toop-edm:v2.1");


    public BRREGGBMHandler(final ToopIncomingHandler toopIncomingHandler) {
        super(QueryType.GBM, toopIncomingHandler);
    }

    static boolean matchesRequestDocumentType(IDocumentTypeIdentifier documentTypeIdentifier) {
        return (documentTypeIdentifier!=null && documentTypeIdentifier.hasSameContent(REQUEST_DOCUMENT_TYPE));
    }

    public void handleIncomingRequest(final IncomingEDMRequest incomingEDMRequest) {
        final EDMRequest edmRequest = incomingEDMRequest.getRequest();

        //Is this a request we support?
        if (!(edmRequest.getPayloadProvider() instanceof IEDMRequestPayloadConcepts)) {
            sendIncomingRequestFailed("Cannot create TOOP response for GBM DocumentRequest: "+edmRequest.getPayloadProvider().getClass().getSimpleName());
            return;
        }

        //Is the request in a structure we support?
        final IEDMRequestPayloadConcepts requestConcepts = (IEDMRequestPayloadConcepts) edmRequest.getPayloadProvider();
        final List<ConceptPojo> concepts = requestConcepts.concepts();
        if (concepts.size() != 1) {
            sendIncomingRequestFailed("Expected exactly one top-level request concept. Got:  "+concepts.size());
            return;
        }

        //Is this a request for REGISTERED_ORGANIZATION?
        final ConceptPojo registeredOrganizationConceptRequest = concepts.get(0);
        if (!registeredOrganizationConceptRequest.getName().equals(EToopConcept.REGISTERED_ORGANIZATION.getAsQName())) {
            sendIncomingRequestFailed("Expected top-level request concept "+EToopConcept.REGISTERED_ORGANIZATION.getAsQName()+". Got: "+registeredOrganizationConceptRequest.getName());
            return;
        }

        //Fetch enhet from enhetsregisteret ( "Finn foretak/selskap" on https://www.brreg.no/ )
        if (edmRequest.getDataSubjectLegalPerson()==null ||
                edmRequest.getDataSubjectLegalPerson().getLegalID()==null ||
                edmRequest.getDataSubjectLegalPerson().getLegalID().isEmpty()) {
            sendIncomingRequestFailed("Request is missing LegalPerson");
            return;
        }
        getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.INFO, "Got incoming request for " + edmRequest.getDataSubjectLegalPerson().getLegalID());
        final String[] legalIdParts = edmRequest.getDataSubjectLegalPerson().getLegalID().split("/");
        final String orgno = legalIdParts[legalIdParts.length-1];
        final Enhet enhet = getToopIncomingHandler().getEnhetsregisterCache().getEnhet(orgno);
        final boolean isError = (enhet == null);

        //Build concepts response
        final ConceptPojo.Builder conceptsBuilder = ConceptPojo.builder()
                .randomID()
                .name(EToopConcept.REGISTERED_ORGANIZATION);

        EDMResponse.BuilderConcept edmResponseBuilder = null;
        EDMErrorResponse.Builder edmErrorResposeBuilder = null;
        if (isError) {
            edmErrorResposeBuilder = EDMErrorResponse.builder();
        } else {
            for (ConceptPojo conceptRequest : registeredOrganizationConceptRequest.children()) {
                if (conceptRequest == null) {
                    continue;
                }

                ConceptPojo.Builder conceptBuilder = null;
                //Enhet
                if (EToopConcept.COMPANY_NAME.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getNavn()!=null) {
                    conceptBuilder = ConceptPojo.builder()
                            .name(EToopConcept.COMPANY_NAME)
                            .valueText(enhet.getNavn());
                } else if (EToopConcept.REGISTRATION_DATE.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getRegistreringsdatoEnhetsregisteret()!=null) {
                    conceptBuilder = ConceptPojo.builder()
                            .name(EToopConcept.REGISTRATION_DATE)
                            .valueDate(LocalDate.parse(enhet.getRegistreringsdatoEnhetsregisteret(), DateTimeFormatter.ofPattern("uuuu-MM-dd")));
                } else if (EToopConcept.COMPANY_CODE.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getOrganisasjonsnummer()!=null) {
                    conceptBuilder = ConceptPojo.builder()
                            .name(EToopConcept.COMPANY_CODE)
                            .valueText(enhet.getOrganisasjonsnummer());
                } else if (EToopConcept.VAT_NUMBER.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getOrganisasjonsnummer()!=null &&
                        enhet.getRegistrertIMvaregisteret()!=null && enhet.getRegistrertIMvaregisteret()) {
                    conceptBuilder = ConceptPojo.builder()
                            .name(EToopConcept.VAT_NUMBER)
                            .valueText(enhet.getOrganisasjonsnummer()+"MVA");
                } else if (EToopConcept.FOUNDATION_DATE.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getStiftelsedato()!=null) {
                    conceptBuilder = ConceptPojo.builder()
                            .name(EToopConcept.FOUNDATION_DATE)
                            .valueDate(LocalDate.parse(enhet.getStiftelsedato(), DateTimeFormatter.ofPattern("uuuu-MM-dd")));
                }

                //Enhet.Organisasjonsform
                if (enhet.getOrganisasjonsform() != null) {
                    if (EToopConcept.COMPANY_TYPE.getAsQName().equals(conceptRequest.getName()) &&
                            enhet.getOrganisasjonsform() != null && enhet.getOrganisasjonsform().getKode() != null) {
                        conceptBuilder = ConceptPojo.builder()
                                .name(EToopConcept.COMPANY_TYPE)
                                .valueText(enhet.getOrganisasjonsform().getKode());
                    }
                }

                //Enhet.Forretningsadresse
                if (enhet.getForretningsadresse() != null) {
                    if (EToopConcept.COUNTRY_NAME.getAsQName().equals(conceptRequest.getName()) &&
                            enhet.getForretningsadresse().getLandkode()!=null) {
                        conceptBuilder = ConceptPojo.builder()
                                .name(EToopConcept.COUNTRY_NAME)
                                .valueText(enhet.getForretningsadresse().getLandkode());
                    } else if (EToopConcept.POSTAL_CODE.getAsQName().equals(conceptRequest.getName()) &&
                            (enhet.getForretningsadresse().getPostnummer()!=null || enhet.getForretningsadresse().getPoststed()!=null)) {
                        StringBuilder sb = new StringBuilder();
                        if (enhet.getForretningsadresse().getPostnummer()!=null) {
                            sb.append(enhet.getForretningsadresse().getPostnummer());
                        }
                        if (enhet.getForretningsadresse().getPoststed()!=null) {
                            if (sb.length()>0) {
                                sb.append(' ');
                            }
                            sb.append(enhet.getForretningsadresse().getPoststed());
                        }
                        conceptBuilder = ConceptPojo.builder()
                                .name(EToopConcept.POSTAL_CODE)
                                .valueText(sb.toString());
                    } else if (EToopConcept.REGION.getAsQName().equals(conceptRequest.getName()) &&
                            enhet.getForretningsadresse().getKommune()!=null) {
                        conceptBuilder = ConceptPojo.builder()
                                .name(EToopConcept.REGION)
                                .valueText(enhet.getForretningsadresse().getKommune());
                    } else if (EToopConcept.STREET_ADDRESS.getAsQName().equals(conceptRequest.getName()) &&
                            enhet.getForretningsadresse().getAdresse()!=null) {
                        StringBuilder sb = new StringBuilder();
                        for (String adresselinje : enhet.getForretningsadresse().getAdresse()) {
                            if (sb.length()>0) {
                                sb.append('\n');
                            }
                            sb.append(adresselinje);
                        }
                        conceptBuilder = ConceptPojo.builder()
                                .name(EToopConcept.STREET_ADDRESS)
                                .valueText(sb.toString());
                    }
                }

                //Enhet.Næringskode1
                if (enhet.getNaeringskode1() != null) {
                    if (EToopConcept.NACE_CODE.getAsQName().equals(conceptRequest.getName()) &&
                            enhet.getNaeringskode1()!=null && enhet.getNaeringskode1().getKode()!=null) {
                        conceptBuilder = ConceptPojo.builder()
                                .name(EToopConcept.NACE_CODE)
                                .valueText(enhet.getNaeringskode1().getKode());
                    }
                }

                if (conceptBuilder == null) {
                    conceptBuilder = ConceptPojo.builder()
                            .name(conceptRequest.getName())
                            .valueErrorCode(EToopDataElementResponseErrorCode.DP_ELE_001);
                }

                conceptsBuilder.addChild(conceptBuilder.id(conceptRequest.getID()).build());
            }
            edmResponseBuilder = EDMResponse.builderConcept().concept(conceptsBuilder.build());
        }

        final MERoutingInformation meRoutingInformation = getRoutingInformation(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_1,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                incomingEDMRequest.getMetadata().getReceiverID() /* incoming receiver is now sender */,
                incomingEDMRequest.getMetadata().getSenderID() /* incoming sender is now receiver */);
        if (meRoutingInformation == null) {
            sendIncomingRequestFailed("Failed to get RoutingInformation");
            return;
        }

        //Create message
        byte[] dataBuf;
        if (isError) {
            edmErrorResposeBuilder.requestID(edmRequest.getRequestID())
                    .specificationIdentifier(CToopEDM.SPECIFICATION_IDENTIFIER_TOOP_EDM_V21)
                    .exception(EDMExceptionPojo.builder()
                            .exceptionType(EEDMExceptionType.INVALID_REQUEST)
                            .severityFailure()
                            .errorMessage("Organization " + orgno + " not found")
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

        final MEMessage meMessage = MEMessage.builder().senderID(incomingEDMRequest.getMetadata().getReceiverID())
                .receiverID(incomingEDMRequest.getMetadata().getSenderID())
                .docTypeID(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_1)
                .processID(EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY)
                .payload(MEPayload.builder()
                        .mimeTypeRegRep()
                        .randomContentID()
                        .data(dataBuf)
                        .build())
                .build();

        //Send response
        try {
            TCAPIHelper.sendAS4Message(meRoutingInformation, meMessage);
        } catch (MEOutgoingException e) {
            sendIncomingRequestFailed("Got exception when sending AS4 message: "+e.getMessage());
        }
    }

    public void handleIncomingResponse(final IncomingEDMResponse incomingEDMResponse) {
        EDMResponse edmResponse = incomingEDMResponse.getResponse();
        getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.INFO, "Got incoming reponse for request " + edmResponse.getRequestID());

        //Is this a request we support?
        IEDMResponsePayloadConcepts conceptPayloadProvider = null;
        for (IEDMResponsePayloadProvider payloadProvider : edmResponse.getAllPayloadProviders()) {
            if (payloadProvider instanceof IEDMResponsePayloadConcepts) {
                conceptPayloadProvider = (IEDMResponsePayloadConcepts) payloadProvider;
            }
        }
        if (conceptPayloadProvider == null) {
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, "No payloadprovider found for response "+edmResponse.getRequestID());
            return;
        }

        //Is the request in a structure we support?
        final List<ConceptPojo> concepts = conceptPayloadProvider.concepts();
        if (concepts.size() != 1) {
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, "Expected exactly one top-level response concept. Got:  "+concepts.size());
            return;
        }

        //Is this a request for REGISTERED_ORGANIZATION?
        final ConceptPojo registeredOrganizationConceptResponse = concepts.get(0);
        if (!registeredOrganizationConceptResponse.getName().equals(EToopConcept.REGISTERED_ORGANIZATION.getAsQName())) {
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, "Expected top-level response concept "+EToopConcept.REGISTERED_ORGANIZATION.getAsQName()+". Got: "+registeredOrganizationConceptResponse.getName());
            return;
        }

        //Populate Enhet
        Enhet enhet = new Enhet();
        for (ConceptPojo conceptResponse : registeredOrganizationConceptResponse.children()) {
            if (conceptResponse==null || conceptResponse.isErrorValue()) {
                continue;
            }

            //Enhet
            if (EToopConcept.COMPANY_NAME.getAsQName().equals(conceptResponse.getName())) {
                enhet.setNavn(conceptResponse.getValue().getAsString());
            } else if (EToopConcept.REGISTRATION_DATE.getAsQName().equals(conceptResponse.getName())) {
                enhet.setRegistreringsdatoEnhetsregisteret(getConceptDateAsString(conceptResponse));
            } else if (EToopConcept.COMPANY_CODE.getAsQName().equals(conceptResponse.getName())) {
                enhet.setOrganisasjonsnummer(conceptResponse.getValue().getAsString());
            } else if (EToopConcept.FOUNDATION_DATE.getAsQName().equals(conceptResponse.getName())) {
                enhet.setStiftelsedato(getConceptDateAsString(conceptResponse));
            }
            //Enhet.Organisasjonsform
            else if (EToopConcept.COMPANY_TYPE.getAsQName().equals(conceptResponse.getName())) {
                if (enhet.getOrganisasjonsform()==null) {
                    enhet.setOrganisasjonsform(new Organisasjonsform());
                }
                enhet.getOrganisasjonsform().setKode(conceptResponse.getValue().getAsString());
            }
            //Enhet.Forretningsadresse
            else if (EToopConcept.COUNTRY_NAME.getAsQName().equals(conceptResponse.getName())) {
                getOrCreateForretningsAdresse(enhet).setLandkode(conceptResponse.getValue().getAsString());
            } else if (EToopConcept.POSTAL_CODE.getAsQName().equals(conceptResponse.getName())) {
                getOrCreateForretningsAdresse(enhet).setPoststed(conceptResponse.getValue().getAsString());
            } else if (EToopConcept.REGION.getAsQName().equals(conceptResponse.getName())) {
                getOrCreateForretningsAdresse(enhet).setKommune(conceptResponse.getValue().getAsString());
            } else if (EToopConcept.STREET_ADDRESS.getAsQName().equals(conceptResponse.getName())) {
                getOrCreateForretningsAdresse(enhet).setAdresse(Collections.singletonList(conceptResponse.getValue().getAsString()));
            }
            //Enhet.Næringskode1
            else if (EToopConcept.COUNTRY_NAME.getAsQName().equals(conceptResponse.getName())) {
                if (enhet.getNaeringskode1()==null) {
                    enhet.setNaeringskode1(new Naeringskode());
                }
                enhet.getNaeringskode1().setKode(conceptResponse.getValue().getAsString());
            }
        }

        getToopIncomingHandler().removePendingRequest(edmResponse.getRequestID(), enhet, HttpStatus.OK);
    }

    public ToopIncomingHandler.ToopResponse getByIdentifier(final String countrycode, final String identifier, final Map<String,Object> properties, final boolean isLegalPerson) {
        CountryCode norway = getToopIncomingHandler().getCountryCodeCache().getCountryCodeById(BRREG_TOOP_ID);
        if (norway == null) {
            final String msg = "Could not find Norway in CountryCode cache!";
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, msg);
            return new ToopIncomingHandler.ToopResponse(HttpStatus.SERVICE_UNAVAILABLE, msg);
        }

        List<CountryCode> receiverCountryCodes = getToopIncomingHandler().getCountryCodeCache().getCountryCode(countrycode, new CountryCodeDocType()
                                                                                                                                    .scheme(REQUEST_DOCUMENT_TYPE.getScheme())
                                                                                                                                    .value(REQUEST_DOCUMENT_TYPE.getValue()));
        CountryCode receiverCountry = (receiverCountryCodes==null || receiverCountryCodes.isEmpty()) ? null : receiverCountryCodes.get(0);
        if (receiverCountry == null) {
            final String msg = "Could not find code \""+countrycode+"\" in CountryCode cache!";
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, msg);
            return new ToopIncomingHandler.ToopResponse(HttpStatus.NOT_FOUND, msg);
        }

        IParticipantIdentifier sender = SimpleIdentifierFactory.INSTANCE.createParticipantIdentifier(CountryCodeCache.COUNTRY_SCHEME, norway.getId());
        IParticipantIdentifier receiver = SimpleIdentifierFactory.INSTANCE.createParticipantIdentifier(CountryCodeCache.COUNTRY_SCHEME, receiverCountry.getId());
        final MERoutingInformation meRoutingInformation = getRoutingInformation(EPredefinedDocumentTypeIdentifier.REGISTEREDORGANIZATION_REGISTERED_ORGANIZATION_TYPE_CONCEPT_CCCEV_TOOP_EDM_V2_1,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                sender,
                receiver);
        if (meRoutingInformation == null) {
            final String msg = "Failed to get RoutingInformation";
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, msg);
            return new ToopIncomingHandler.ToopResponse(HttpStatus.SERVICE_UNAVAILABLE, msg);
        }

        //Build concepts request
        final ConceptPojo.Builder conceptsBuilder = ConceptPojo.builder()
                .randomID()
                .name(EToopConcept.REGISTERED_ORGANIZATION);

        EToopConcept[] requestedConcepts = {EToopConcept.COMPANY_NAME,
                EToopConcept.REGISTRATION_DATE,
                EToopConcept.COMPANY_CODE,
                EToopConcept.VAT_NUMBER,
                EToopConcept.FOUNDATION_DATE,
                EToopConcept.COMPANY_TYPE,
                EToopConcept.COUNTRY_NAME,
                EToopConcept.POSTAL_CODE,
                EToopConcept.REGION,
                EToopConcept.STREET_ADDRESS,
                EToopConcept.NACE_CODE};
        for (EToopConcept requestedConcept : requestedConcepts) {
            conceptsBuilder.addChild(ConceptPojo.builder()
                    .randomID()
                    .name(requestedConcept)
                    .build());
        }

        //Create message
        EDMRequest.BuilderConcept edmRequestBuilder = EDMRequest.builderConcept()
                .concept(conceptsBuilder.build())
                .randomID()
                .dataConsumer(norway())
                .issueDateTimeNow()
                .specificationIdentifier(CToopEDM.SPECIFICATION_IDENTIFIER_TOOP_EDM_V21);

        if (isLegalPerson) {
            edmRequestBuilder.dataSubject(BusinessPojo.builder()
                    .legalIDSchemeID(EToopIdentifierType.EIDAS)
                    .legalID(norway.getCode()+"/"+receiverCountry.getCode()+"/"+identifier)
                    .build());
        } else {
            edmRequestBuilder.dataSubject(PersonPojo.builder()
                    .idSchemeID(EToopIdentifierType.EIDAS)
                    .id(norway.getCode()+"/"+receiverCountry.getCode()+"/"+identifier)
                    .firstName((String)properties.getOrDefault("firstname", "Any"))
                    .familyName((String)properties.getOrDefault("lastname", "Any"))
                    .birthDate((LocalDate)properties.getOrDefault("birthdate", LocalDate.of(1970, 1, 1)))
                    .build());
        }

        EDMRequest edmRequest = edmRequestBuilder.build();

        byte[] dataBuf = edmRequest.getWriter().getAsBytes();
        final MEMessage meMessage = MEMessage.builder().senderID(sender)
                .receiverID(receiver)
                .docTypeID(EPredefinedDocumentTypeIdentifier.REGISTEREDORGANIZATION_REGISTERED_ORGANIZATION_TYPE_CONCEPT_CCCEV_TOOP_EDM_V2_1)
                .processID(EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY)
                .payload(MEPayload.builder()
                        .mimeTypeRegRep()
                        .randomContentID()
                        .data(dataBuf)
                        .build())
                .build();

        //Send request
        try {
            TCAPIHelper.sendAS4Message(meRoutingInformation, meMessage);
        } catch (MEOutgoingException e) {
            final String msg = "Got exception when sending AS4 message: "+e.getMessage();
            getToopIncomingHandler().getLoggerHandler().log(LoggerHandler.Level.ERROR, msg);
            return new ToopIncomingHandler.ToopResponse(HttpStatus.SERVICE_UNAVAILABLE, msg);
        }

        return getToopIncomingHandler().addPendingRequest(this, edmRequest.getRequestID());
    }

}
