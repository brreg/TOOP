package no.brreg.toop;

import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.xsds.bdxr.smp1.EndpointType;
import eu.toop.commons.codelist.EPredefinedDocumentTypeIdentifier;
import eu.toop.commons.codelist.EPredefinedProcessIdentifier;
import eu.toop.connector.api.me.incoming.*;
import eu.toop.connector.api.me.model.MEMessage;
import eu.toop.connector.api.me.outgoing.MEOutgoingException;
import eu.toop.connector.api.me.outgoing.MERoutingInformation;
import eu.toop.connector.app.api.TCAPIHelper;
import eu.toop.edm.EDMErrorResponse;
import eu.toop.edm.EDMRequest;
import eu.toop.edm.EDMResponse;
import eu.toop.edm.model.ConceptPojo;
import eu.toop.edm.pilot.gbm.EToopConcept;
import eu.toop.edm.request.IEDMRequestPayloadConcepts;
import no.brreg.toop.generated.model.Enhet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
        EDMRequest edmRequest = incomingEDMRequest.getRequest();

        //Is this a request we support?
        if (!(edmRequest.getPayloadProvider() instanceof IEDMRequestPayloadConcepts)) {
            LOGGER.info("Cannot create TOOP response for DocumentRequest ({})", edmRequest.getPayloadProvider().getClass().getSimpleName());
            return;
        }

        //Is the request in a structure we support?
        IEDMRequestPayloadConcepts requestConcepts = (IEDMRequestPayloadConcepts) edmRequest.getPayloadProvider();
        List<ConceptPojo> concepts = requestConcepts.concepts();
        if (concepts.size() != 1) {
            LOGGER.info("Expected exactly one top-level request concept. Got {}", concepts.size());
            return;
        }

        //Is this a request for REGISTERED_ORGANIZATION?
        ConceptPojo registeredOrganizationConceptRequest = concepts.get(0);
        if (!registeredOrganizationConceptRequest.getName().equals(EToopConcept.REGISTERED_ORGANIZATION.getAsQName())) {
            LOGGER.info("Expected top-level request concept {}. Got {}", EToopConcept.REGISTERED_ORGANIZATION.getAsQName(), registeredOrganizationConceptRequest.getName());
            return;
        }

        //Fetch enhet from enhetsregisteret ( "Finn foretak/selskap" on https://www.brreg.no/ )
        LOGGER.info("Got incoming request for {}", edmRequest.getDataSubjectLegalPerson().getLegalID());
        String[] legalIdParts = edmRequest.getDataSubjectLegalPerson().getLegalID().split("/");
        String orgno = legalIdParts[legalIdParts.length-1];
        Enhet enhet = enhetsregisterCache.getEnhet(orgno);

        //Build response
        final EDMResponse.BuilderConcept edmResponseBuilder = EDMResponse.builderConcept();
        if (enhet != null) {
            for (ConceptPojo conceptRequest : registeredOrganizationConceptRequest.children()) {
                if (conceptRequest == null) {
                    continue;
                }

                ConceptPojo.Builder conceptPojoBuilder = null;
                if (EToopConcept.COMPANY_NAME.getAsQName().equals(conceptRequest.getName()) &&
                    enhet.getNavn()!=null) {
                    conceptPojoBuilder = ConceptPojo.builder()
                        .name(EToopConcept.COMPANY_NAME)
                        .valueText(enhet.getNavn());
                } else if (EToopConcept.COMPANY_TYPE.getAsQName().equals(conceptRequest.getName()) &&
                           enhet.getOrganisasjonsform()!=null && enhet.getOrganisasjonsform().getKode()!=null) {
                    conceptPojoBuilder = ConceptPojo.builder()
                        .name(EToopConcept.COMPANY_TYPE)
                        .valueText(enhet.getOrganisasjonsform().getKode());
                } else if (EToopConcept.REGISTRATION_DATE.getAsQName().equals(conceptRequest.getName()) &&
                           enhet.getRegistreringsdatoEnhetsregisteret()!=null) {
                    conceptPojoBuilder = ConceptPojo.builder()
                        .name(EToopConcept.REGISTRATION_DATE)
                        .valueDate(LocalDate.parse(enhet.getRegistreringsdatoEnhetsregisteret(), DateTimeFormatter.ofPattern("YYYY-MM-dd")));
                } else if (EToopConcept.COMPANY_CODE.getAsQName().equals(conceptRequest.getName()) &&
                           enhet.getOrganisasjonsnummer()!=null) {
                    conceptPojoBuilder = ConceptPojo.builder()
                        .name(EToopConcept.COMPANY_CODE)
                        .valueText(enhet.getOrganisasjonsnummer());
                } else if (EToopConcept.VAT_NUMBER.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getOrganisasjonsnummer()!=null &&
                        enhet.getRegistrertIMvaregisteret()!=null && enhet.getRegistrertIMvaregisteret().booleanValue()==true) {
                    conceptPojoBuilder = ConceptPojo.builder()
                            .name(EToopConcept.VAT_NUMBER)
                            .valueText(enhet.getOrganisasjonsnummer()+"MVA");
                } else if (EToopConcept.FOUNDATION_DATE.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getStiftelsedato()!=null) {
                    conceptPojoBuilder = ConceptPojo.builder()
                        .name(EToopConcept.FOUNDATION_DATE)
                        .valueDate(LocalDate.parse(enhet.getStiftelsedato(), DateTimeFormatter.ofPattern("YYYY-MM-dd")));
                } else if (EToopConcept.NACE_CODE.getAsQName().equals(conceptRequest.getName()) &&
                        enhet.getNaeringskode1()!=null && enhet.getNaeringskode1().getKode()!=null) {
                    conceptPojoBuilder = ConceptPojo.builder()
                        .name(EToopConcept.NACE_CODE)
                        .valueText(enhet.getNaeringskode1().getKode());
                }
                //TODO

                if (conceptPojoBuilder != null) {
                    edmResponseBuilder.addConcept(conceptPojoBuilder.id(conceptRequest.getID()).build());
                }

    /*
        ACTIVITY_DESCRIPTION("ActivityDescription"),
        BIRTH_DATE("LegalRepresentativeBirthDate"),
        CAPTIAL_TYPE("CapitalType"),
        COUNTRY_NAME("CountryName"),
        EMAIL_ADDRESS("EmailAddress"),
        FAMILY_NAME("LegalRepresentativeFamilyName"),
        FAX_NUMBER("FaxNumber"),
        GIVEN_NAME("LegalRepresentativeGivenName"),
        HAS_LEGAL_REPRESENTATIVE("HasLegalRepresentative"),
        LEGAL_STATUS("LegalStatus"),
        LEGAL_STATUS_EFFECTIVE_DATE("LegalStatusEffectiveDate"),
        LOCALITY("Locality"),
        PERSON("Person"),
        POSTAL_CODE("PostalCode"),
        REGION("Region"),
        REGISTERED_ORGANIZATION("RegisteredOrganization"),
        REGISTRATION_NUMBER("RegistrationNumber"),
        SOCIAL_SEC_NUMBER("SSNumber"),
        STREET_ADDRESS("StreetAddress"),
        TELEPHONE_NUMBER("TelephoneNumber"),
     */
            }
        }

        //Query for SMP Endpoint
        final String transportProtocol = ESMPTransportProfile.TRANSPORT_PROFILE_BDXR_AS4.getID();
        EndpointType endpointType = TCAPIHelper.querySMPEndpoint(incomingEDMRequest.getMetadata().getSenderID(),
                EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_0,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                transportProtocol);

        //Did we find an endpoint?
        if (endpointType == null) {
            LOGGER.info("SME lookup failed for {}", incomingEDMRequest.getMetadata().getSenderID().toString());
            return;
        }

        //Create x509Certificate, we only have byte[]
        X509Certificate certificate;
        try (InputStream is = new ByteArrayInputStream(endpointType.getCertificate())){
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) certificateFactory.generateCertificate(is);
        } catch (CertificateException | IOException e) {
            LOGGER.info("Failed to get CertificateFactory instance: " + e.getMessage());
            return;
        }

        //Create routing information
        MERoutingInformation meRoutingInformation = new MERoutingInformation(incomingEDMRequest.getMetadata().getReceiverID(),
                incomingEDMRequest.getMetadata().getSenderID(),
                EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_0,
                EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY,
                transportProtocol,
                endpointType.getEndpointURI(),
                certificate);

        //Create message
        MEMessage meMessage = MEMessage.builder().senderID(incomingEDMRequest.getMetadata().getReceiverID())
                                                 .receiverID(incomingEDMRequest.getMetadata().getSenderID())
                                                 .docTypeID(EPredefinedDocumentTypeIdentifier.QUERYRESPONSE_TOOP_EDM_V2_0)
                                                 .processID(EPredefinedProcessIdentifier.URN_EU_TOOP_PROCESS_DATAQUERY)
                                                 .addPayload(x -> x.mimeTypeRegRep()
                                                                   .randomContentID()
                                                                   .data(edmResponseBuilder.build().getWriter().getAsBytes()))
                                                 .build();

        //Send message
        try {
            TCAPIHelper.sendAS4Message(meRoutingInformation, meMessage);
        } catch (MEOutgoingException e) {
            LOGGER.info("Got exception when sending AS4 message: " + e.getMessage());
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
}
