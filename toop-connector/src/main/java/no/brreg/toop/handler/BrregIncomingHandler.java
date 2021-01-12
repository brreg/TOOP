package no.brreg.toop;

// This code is Public Domain. See LICENSE

import com.helger.commons.collection.impl.CommonsArrayList;
import com.helger.dns.dnsjava.DnsjavaInit;
import com.helger.dns.ip.IPV4Addr;
import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.SimpleIdentifierFactory;
import com.helger.xsds.bdxr.smp1.EndpointType;
import eu.toop.commons.codelist.EPredefinedDocumentTypeIdentifier;
import eu.toop.commons.codelist.EPredefinedProcessIdentifier;
import eu.toop.connector.api.me.incoming.*;
import eu.toop.connector.api.me.model.MEMessage;
import eu.toop.connector.api.me.model.MEPayload;
import eu.toop.connector.api.me.outgoing.MEOutgoingException;
import eu.toop.connector.api.me.outgoing.MERoutingInformation;
import eu.toop.connector.app.api.TCAPIHelper;
import eu.toop.edm.CToopEDM;
import eu.toop.edm.EDMErrorResponse;
import eu.toop.edm.EDMRequest;
import eu.toop.edm.EDMResponse;
import eu.toop.edm.error.*;
import eu.toop.edm.model.*;
import eu.toop.edm.pilot.gbm.EToopConcept;
import eu.toop.edm.request.IEDMRequestPayloadConcepts;
import eu.toop.edm.response.IEDMResponsePayloadConcepts;
import eu.toop.edm.response.IEDMResponsePayloadProvider;
import eu.toop.regrep.ERegRepResponseStatus;
import no.brreg.toop.generated.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;


@Component
public class BrregIncomingHandler implements IMEIncomingHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrregIncomingHandler.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(28);
    private static final String NORWEGIAN_COUNTRYCODE = "NO";
    private static final InetAddress[] dnsServers = {IPV4Addr.getAsInetAddress (1, 1, 1, 1),
                                                     IPV4Addr.getAsInetAddress (8, 8, 8, 8),
                                                     IPV4Addr.getAsInetAddress (1, 0, 0, 1),
                                                     IPV4Addr.getAsInetAddress (8, 8, 4, 4)};


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

    private class Request {
        private final String id;
        private final Object lock = new Object();
        private final ToopResponse response = new ToopResponse();

        public Request(final String id) {
            this.id = id;
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
    private final Map<String, Request> requestMap = new HashMap<>();
    private static final Object requestMapLock = new Object();


    @Override
    public void handleIncomingRequest(@Nonnull IncomingEDMRequest incomingEDMRequest) throws MEIncomingException {
        final EDMRequest edmRequest = incomingEDMRequest.getRequest();

        //Is this a request we support?
        if (!(edmRequest.getPayloadProvider() instanceof IEDMRequestPayloadConcepts)) {
            sendIncomingRequestFailed("Cannot create TOOP response for DocumentRequest: "+edmRequest.getPayloadProvider().getClass().getSimpleName());
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
        loggerHandler.log(LoggerHandler.Level.INFO, "Got incoming request for " + edmRequest.getDataSubjectLegalPerson().getLegalID());
        final String[] legalIdParts = edmRequest.getDataSubjectLegalPerson().getLegalID().split("/");
        final String orgno = legalIdParts[legalIdParts.length-1];
        final Enhet enhet = enhetsregisterCache.getEnhet(orgno);
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

    @Override
    public void handleIncomingResponse(@Nonnull IncomingEDMResponse incomingEDMResponse) throws MEIncomingException {
        EDMResponse edmResponse = incomingEDMResponse.getResponse();
        loggerHandler.log(LoggerHandler.Level.INFO, "Got incoming reponse for request " + edmResponse.getRequestID());

        //Is this a request we support?
        IEDMResponsePayloadConcepts conceptPayloadProvider = null;
        for (IEDMResponsePayloadProvider payloadProvider : edmResponse.getAllPayloadProviders()) {
            if (payloadProvider instanceof IEDMResponsePayloadConcepts) {
                conceptPayloadProvider = (IEDMResponsePayloadConcepts) payloadProvider;
            }
        }
        if (conceptPayloadProvider == null) {
            loggerHandler.log(LoggerHandler.Level.ERROR, "No payloadprovider found for response "+edmResponse.getRequestID());
            return;
        }

        //Is the request in a structure we support?
        final List<ConceptPojo> concepts = conceptPayloadProvider.concepts();
        if (concepts.size() != 1) {
            loggerHandler.log(LoggerHandler.Level.ERROR, "Expected exactly one top-level response concept. Got:  "+concepts.size());
            return;
        }

        //Is this a request for REGISTERED_ORGANIZATION?
        final ConceptPojo registeredOrganizationConceptResponse = concepts.get(0);
        if (!registeredOrganizationConceptResponse.getName().equals(EToopConcept.REGISTERED_ORGANIZATION.getAsQName())) {
            loggerHandler.log(LoggerHandler.Level.ERROR, "Expected top-level response concept "+EToopConcept.REGISTERED_ORGANIZATION.getAsQName()+". Got: "+registeredOrganizationConceptResponse.getName());
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

        synchronized (requestMapLock) {
            Request request = requestMap.get(edmResponse.getRequestID());
            if (request == null) {
                loggerHandler.log(LoggerHandler.Level.INFO, "Request for response "+edmResponse.getRequestID()+" already removed from pending queue");
                return;
            }

            synchronized(request) {
                request.getResponse().setEnhet(enhet);
                request.getResponse().setStatus(HttpStatus.OK);
                requestMap.remove(request);
                synchronized (request.getLock()) {
                    request.getLock().notify();
                }
            }
        }
    }

    @Override
    public void handleIncomingErrorResponse(@Nonnull IncomingEDMErrorResponse incomingEDMErrorResponse) throws MEIncomingException {
        EDMErrorResponse edmErrorResponse = incomingEDMErrorResponse.getErrorResponse();
        loggerHandler.log(LoggerHandler.Level.INFO, "Got incoming error reponse for request " + edmErrorResponse.getRequestID());

        synchronized (requestMapLock) {
            Request request = requestMap.get(edmErrorResponse.getRequestID());
            if (request == null) {
                loggerHandler.log(LoggerHandler.Level.INFO, "Request for error response "+edmErrorResponse.getRequestID()+" already removed from pending queue");
                return;
            }

            synchronized(request) {
                request.getResponse().setStatus(HttpStatus.NOT_FOUND);
                requestMap.remove(request);
                synchronized(request.getLock()) {
                    request.getLock().notify();
                }
            }
        }
    }

    private MERoutingInformation getRoutingInformation(final IDocumentTypeIdentifier docTypeIdentifier, final IProcessIdentifier processIdentifier,
                                                       IParticipantIdentifier senderId, final IParticipantIdentifier receiverId) {
        //Query for SMP Endpoint
        final String transportProtocol = ESMPTransportProfile.TRANSPORT_PROFILE_BDXR_AS4.getID();
        EndpointType endpointType = null;
        for (InetAddress dnsServer : dnsServers) {
            try {
                DnsjavaInit.initWithCustomDNSServers(new CommonsArrayList<>(dnsServer));
                endpointType = TCAPIHelper.querySMPEndpoint(receiverId, docTypeIdentifier, processIdentifier, transportProtocol);
                break; //We have got a response. Break out of for-loop
            } catch (Exception e) {
                loggerHandler.log(LoggerHandler.Level.INFO, "Resolve using "+dnsServer.toString()+" failed: " + e.getMessage());
            }
        }

        //Did we find an endpoint?
        if (endpointType == null) {
            loggerHandler.log(LoggerHandler.Level.ERROR, "SME lookup failed for "+receiverId);
            return null;
        }

        //Create x509Certificate, we only have byte[]
        final X509Certificate certificate;
        try (InputStream is = new ByteArrayInputStream(endpointType.getCertificate())){
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) certificateFactory.generateCertificate(is);
        } catch (CertificateException | IOException e) {
            loggerHandler.log(LoggerHandler.Level.ERROR, "Failed to get CertificateFactory instance: " + e.getMessage());
            return null;
        }

        //Create routing information
        return new MERoutingInformation(senderId, receiverId,
                docTypeIdentifier,
                processIdentifier,
                transportProtocol,
                endpointType.getEndpointURI(),
                certificate);
    }

    private AgentPojo norway() {
        CountryCode norway = countryCodeCache.getCountryCode(NORWEGIAN_COUNTRYCODE);
        if (norway == null) {
            loggerHandler.log(LoggerHandler.Level.ERROR, "Could not find Norway in CountryCode cache!");
            return null;
        }

        return AgentPojo.builder()
                .id(norway.getId())
                .idSchemeID(EToopIdentifierType.EIDAS)
                .name("Brønnøysund Register Centre")
                .address(AddressPojo.builder()
                        .fullAddress("Brønnøysundregistrene, Havnegata 48, 8900 Brønnøysund, Norway")
                        .streetName("Havnegata 48")
                        .postalCode("8910 Brønnøysund")
                        .town("Brønnøysund")
                        .countryCode(norway.getCode())
                        .build())
                .build();
    }

    private void sendIncomingRequestFailed(final String errorMsg) {
        loggerHandler.log(LoggerHandler.Level.ERROR, errorMsg);
        //TODO, send error response
    }

    public ToopResponse getByIdentifier(final String countrycode, final String identifier, final Map<String,Object> properties, final boolean isLegalPerson) {
        CountryCode norway = countryCodeCache.getCountryCode(NORWEGIAN_COUNTRYCODE);
        if (norway == null) {
            final String msg = "Could not find Norway in CountryCode cache!";
            loggerHandler.log(LoggerHandler.Level.ERROR, msg);
            return new ToopResponse(HttpStatus.SERVICE_UNAVAILABLE, msg);
        }

        CountryCode receiverCountry = countryCodeCache.getCountryCode(countrycode);
        if (receiverCountry == null) {
            final String msg = "Could not find code \""+countrycode+"\" in CountryCode cache!";
            loggerHandler.log(LoggerHandler.Level.ERROR, msg);
            return new ToopResponse(HttpStatus.NOT_FOUND, msg);
        }

        IParticipantIdentifier sender = SimpleIdentifierFactory.INSTANCE.createParticipantIdentifier(CountryCodeCache.COUNTRY_SCHEME, norway.getId());
        IParticipantIdentifier receiver = SimpleIdentifierFactory.INSTANCE.createParticipantIdentifier(CountryCodeCache.COUNTRY_SCHEME, receiverCountry.getId());
        final MERoutingInformation meRoutingInformation = getRoutingInformation(EPredefinedDocumentTypeIdentifier.REGISTEREDORGANIZATION_REGISTERED_ORGANIZATION_TYPE_CONCEPT_CCCEV_TOOP_EDM_V2_1,
                                                                                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                                                                                sender,
                                                                                receiver);
        if (meRoutingInformation == null) {
            final String msg = "Failed to get RoutingInformation";
            loggerHandler.log(LoggerHandler.Level.ERROR, msg);
            return new ToopResponse(HttpStatus.SERVICE_UNAVAILABLE, msg);
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
            loggerHandler.log(LoggerHandler.Level.ERROR, msg);
            return new ToopResponse(HttpStatus.SERVICE_UNAVAILABLE, msg);
        }

        Request request = new Request(edmRequest.getRequestID());
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
            return new ToopResponse(HttpStatus.GATEWAY_TIMEOUT, msg);
        } finally {
            synchronized(requestMapLock) {
                requestMap.remove(request.getId());
            }
        }
    }

    private Adresse getOrCreateForretningsAdresse(final Enhet enhet) {
        if (enhet.getForretningsadresse() == null) {
            enhet.setForretningsadresse(new Adresse());
        }
        return enhet.getForretningsadresse();
    }

    private LocalDate getConceptDate(final ConceptPojo concept) {
        if (concept==null || concept.getValue()==null) {
            return null;
        }

        LocalDate conceptDate = concept.getValue().getDate();
        if (conceptDate==null && concept.getValue().getAsString()!=null) {
            loggerHandler.log(LoggerHandler.Level.INFO, "Concept date did not have dateValue. Trying to parse textValue \"" + concept.getValue().getAsString() + "\"");
            try {
                conceptDate = LocalDate.parse(concept.getValue().getAsString(), DateTimeFormatter.ofPattern("uuuu-MM-dd"));
            } catch (DateTimeParseException e) {
                loggerHandler.log(LoggerHandler.Level.INFO, "Failed to parse string as date: " + concept.getValue().getAsString());
                conceptDate = null;
            }
        }
        return conceptDate;
    }

    private String getConceptDateAsString(final ConceptPojo concept) {
        final LocalDate conceptDate = getConceptDate(concept);
        return conceptDate==null ? null : conceptDate.format(DateTimeFormatter.ofPattern("uuuu-MM-dd"));
    }

}
