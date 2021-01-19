package no.brreg.toop.handler;

// This code is Public Domain. See LICENSE

import com.helger.commons.collection.impl.CommonsArrayList;
import com.helger.dns.dnsjava.DnsjavaInit;
import com.helger.dns.ip.IPV4Addr;
import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.xsds.bdxr.smp1.EndpointType;
import eu.toop.connector.api.me.incoming.IncomingEDMRequest;
import eu.toop.connector.api.me.incoming.IncomingEDMResponse;
import eu.toop.connector.api.me.outgoing.MERoutingInformation;
import eu.toop.connector.app.api.TCAPIHelper;
import eu.toop.edm.model.*;
import no.brreg.toop.LoggerHandler;
import no.brreg.toop.generated.model.Adresse;
import no.brreg.toop.generated.model.CountryCode;
import no.brreg.toop.generated.model.Enhet;
import no.brreg.toop.generated.model.QueryType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;


public abstract class BRREGBaseHandler {

    private QueryType queryType;
    private ToopIncomingHandler toopIncomingHandler;

    public static final String NORWEGIAN_COUNTRYCODE = "NO";

    private static final InetAddress[] dnsServers = {IPV4Addr.getAsInetAddress (1, 1, 1, 1),
            IPV4Addr.getAsInetAddress (8, 8, 8, 8),
            IPV4Addr.getAsInetAddress (1, 0, 0, 1),
            IPV4Addr.getAsInetAddress (8, 8, 4, 4)};


    private BRREGBaseHandler() {}

    public BRREGBaseHandler(final QueryType queryType, final ToopIncomingHandler toopIncomingHandler) {
        this.queryType = queryType;
        this.toopIncomingHandler = toopIncomingHandler;
    }

    public QueryType getQueryType() {
        return queryType;
    }

    public ToopIncomingHandler getToopIncomingHandler() {
        return toopIncomingHandler;
    }

    abstract void handleIncomingRequest(final IncomingEDMRequest incomingEDMRequest);
    abstract void handleIncomingResponse(final IncomingEDMResponse incomingEDMResponse);

    protected void sendIncomingRequestFailed(final String errorMsg) {
        toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.ERROR, errorMsg);
        //TODO, send error response
    }

    protected MERoutingInformation getRoutingInformation(final IDocumentTypeIdentifier docTypeIdentifier, final IProcessIdentifier processIdentifier,
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
                toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.INFO, "Resolve using "+dnsServer.toString()+" failed: " + e.getMessage());
            }
        }

        //Did we find an endpoint?
        if (endpointType == null) {
            toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.ERROR, "SME lookup failed for "+receiverId);
            return null;
        }

        //Create x509Certificate, we only have byte[]
        final X509Certificate certificate;
        try (InputStream is = new ByteArrayInputStream(endpointType.getCertificate())){
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            certificate = (X509Certificate) certificateFactory.generateCertificate(is);
        } catch (CertificateException | IOException e) {
            toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.ERROR, "Failed to get CertificateFactory instance: " + e.getMessage());
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

    protected AgentPojo norway() {
        CountryCode norway = toopIncomingHandler.getCountryCodeCache().getCountryCode(getQueryType(), NORWEGIAN_COUNTRYCODE);
        if (norway == null) {
            toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.ERROR, "Could not find Norway in CountryCode cache!");
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

    protected Adresse getOrCreateForretningsAdresse(final Enhet enhet) {
        if (enhet.getForretningsadresse() == null) {
            enhet.setForretningsadresse(new Adresse());
        }
        return enhet.getForretningsadresse();
    }

    protected LocalDate getConceptDate(final ConceptPojo concept) {
        if (concept==null || concept.getValue()==null) {
            return null;
        }

        LocalDate conceptDate = concept.getValue().getDate();
        if (conceptDate==null && concept.getValue().getAsString()!=null) {
            toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.INFO, "Concept date did not have dateValue. Trying to parse textValue \"" + concept.getValue().getAsString() + "\"");
            try {
                conceptDate = LocalDate.parse(concept.getValue().getAsString(), DateTimeFormatter.ofPattern("uuuu-MM-dd"));
            } catch (DateTimeParseException e) {
                toopIncomingHandler.getLoggerHandler().log(LoggerHandler.Level.INFO, "Failed to parse string as date: " + concept.getValue().getAsString());
                conceptDate = null;
            }
        }
        return conceptDate;
    }

    protected String getConceptDateAsString(final ConceptPojo concept) {
        final LocalDate conceptDate = getConceptDate(concept);
        return conceptDate==null ? null : conceptDate.format(DateTimeFormatter.ofPattern("uuuu-MM-dd"));
    }

}
