package no.brreg.toop;

import com.helger.peppol.smp.ESMPTransportProfile;
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
import eu.toop.edm.model.AddressPojo;
import eu.toop.edm.model.AgentPojo;
import eu.toop.edm.model.ConceptPojo;
import eu.toop.edm.model.EToopIdentifierType;
import eu.toop.edm.pilot.gbm.EToopConcept;
import eu.toop.edm.request.IEDMRequestPayloadConcepts;
import eu.toop.regrep.ERegRepResponseStatus;
import no.brreg.toop.generated.model.Enhet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;


@Component
public class BrregIncomingHandler implements IMEIncomingHandler {
    private static Logger LOGGER = LoggerFactory.getLogger(BrregIncomingHandler.class);

    @Autowired
    private EnhetsregisterCache enhetsregisterCache;


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
        LOGGER.info("Got incoming request for {}", edmRequest.getDataSubjectLegalPerson().getLegalID());
        final String[] legalIdParts = edmRequest.getDataSubjectLegalPerson().getLegalID().split("/");
        final String orgno = legalIdParts[legalIdParts.length-1];
        final Enhet enhet = enhetsregisterCache.getEnhet(orgno);

        //Build concepts response
        final ConceptPojo.Builder conceptsBuilder = ConceptPojo.builder()
                .randomID()
                .name(EToopConcept.REGISTERED_ORGANIZATION);

        if (enhet != null) {
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
                        enhet.getRegistrertIMvaregisteret()!=null && enhet.getRegistrertIMvaregisteret().booleanValue()==true) {
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

                if (conceptBuilder != null) {
                    conceptsBuilder.addChild(conceptBuilder.id(conceptRequest.getID()).build());
                }
            }
        }
        final EDMResponse.BuilderConcept edmResponseBuilder = EDMResponse.builderConcept().concept(conceptsBuilder.build());

        //Query for SMP Endpoint
        final String transportProtocol = ESMPTransportProfile.TRANSPORT_PROFILE_BDXR_AS4.getID();
        final EndpointType endpointType = TCAPIHelper.querySMPEndpoint(incomingEDMRequest.getMetadata().getSenderID(),
                EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_0,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                transportProtocol);

        //Did we find an endpoint?
        if (endpointType == null) {
            sendIncomingRequestFailed("SME lookup failed for "+incomingEDMRequest.getMetadata().getSenderID().toString());
            return;
        }

        //Create x509Certificate, we only have byte[]
        final X509Certificate certificate;
        try (InputStream is = new ByteArrayInputStream(endpointType.getCertificate())){
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) certificateFactory.generateCertificate(is);
        } catch (CertificateException | IOException e) {
            sendIncomingRequestFailed("Failed to get CertificateFactory instance: " + e.getMessage());
            return;
        }

        //Create routing information
        final MERoutingInformation meRoutingInformation = new MERoutingInformation(incomingEDMRequest.getMetadata().getReceiverID(),
                incomingEDMRequest.getMetadata().getSenderID(),
                EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_0,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                transportProtocol,
                endpointType.getEndpointURI(),
                certificate);

        //Create message
        edmResponseBuilder.requestID(edmRequest.getRequestID())
                          .dataProvider(AgentPojo.builder()
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
                          .issueDateTimeNow()
                          .specificationIdentifier(CToopEDM.SPECIFICATION_IDENTIFIER_TOOP_EDM_V20)
                          .responseStatus(ERegRepResponseStatus.SUCCESS);

        LOGGER.debug("edmResponseBuilder: " + new String(edmResponseBuilder.build().getWriter().getAsBytes(), StandardCharsets.UTF_8));

        final MEMessage meMessage = MEMessage.builder().senderID(incomingEDMRequest.getMetadata().getReceiverID())
                                                 .receiverID(incomingEDMRequest.getMetadata().getSenderID())
                                                 .docTypeID(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_0)
                                                 .processID(EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY)
                                                 .payload(MEPayload.builder()
                                                                .mimeTypeRegRep()
                                                                .randomContentID()
                                                                .data(edmResponseBuilder.build().getWriter().getAsBytes())
                                                                .build())
                                                 .build();

        //Send message
        try {
            TCAPIHelper.sendAS4Message(meRoutingInformation, meMessage);
        } catch (MEOutgoingException e) {
            sendIncomingRequestFailed("Got exception when sending AS4 message: "+e.getMessage());
        }
    }

    @Override
    public void handleIncomingResponse(@Nonnull IncomingEDMResponse incomingEDMResponse) throws MEIncomingException {
        EDMResponse edmResponse = incomingEDMResponse.getResponse();
    }

    @Override
    public void handleIncomingErrorResponse(@Nonnull IncomingEDMErrorResponse incomingEDMErrorResponse) throws MEIncomingException {
        EDMErrorResponse edmErrorResponse = incomingEDMErrorResponse.getErrorResponse();
    }

    private void sendIncomingRequestFailed(final String errorMsg) {
        LOGGER.error(errorMsg);
        //TODO, send error response
    }

}
