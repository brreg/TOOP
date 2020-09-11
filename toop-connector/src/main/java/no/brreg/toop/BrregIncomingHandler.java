package no.brreg.toop;

import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.peppolid.factory.SimpleIdentifierFactory;
import com.helger.xsds.bdxr.smp1.EndpointType;
import eu.toop.connector.api.me.incoming.*;
import eu.toop.connector.api.me.model.MEMessage;
import eu.toop.connector.api.me.model.MEPayload;
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

        //Fetch enhet from enhetsregisteret ( "Finn foretak/selskap" on https://www.brreg.no/ )
        String[] legalIdParts = edmRequest.getDataSubjectLegalPerson().getLegalID().split("/");
        String orgno = legalIdParts[legalIdParts.length-1];
        Enhet enhet = enhetsregisterCache.getEnhet(orgno);

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

        //Build response
        MEPayload.Builder payloadBuilder = MEPayload.builder();
        for (ConceptPojo conceptRequest : registeredOrganizationConceptRequest.children()) {
            if (conceptRequest == null) {
                continue;
            }
            //TODO
        }

        //Query for SMP Endpoint
        final IDocumentTypeIdentifier docTypeID = SimpleIdentifierFactory.INSTANCE.createDocumentTypeIdentifierWithDefaultScheme("");
        final IProcessIdentifier processID = SimpleIdentifierFactory.INSTANCE.createProcessIdentifierWithDefaultScheme("");
        final String transportProtocol = ESMPTransportProfile.TRANSPORT_PROFILE_BDXR_AS4.getID();
        EndpointType endpointType = TCAPIHelper.querySMPEndpoint(incomingEDMRequest.getMetadata().getSenderID(),
                docTypeID,
                processID,
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
                docTypeID,
                processID,
                transportProtocol,
                endpointType.getEndpointURI(),
                certificate);

        //Create message
        MEMessage meMessage = MEMessage.builder().senderID(incomingEDMRequest.getMetadata().getReceiverID())
                                                 .receiverID(incomingEDMRequest.getMetadata().getSenderID())
                                                 .docTypeID(docTypeID)
                                                 .processID(processID)
                                                 .payload(payloadBuilder).build();

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
